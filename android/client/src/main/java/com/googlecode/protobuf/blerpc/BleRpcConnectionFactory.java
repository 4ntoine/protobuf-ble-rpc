package com.googlecode.protobuf.blerpc;

import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.Handler;
import android.os.ParcelUuid;
import com.googlecode.protobuf.socketrpc.RpcConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Client BLE RPC connection factory
 */
public class BleRpcConnectionFactory extends BluetoothGattCallback implements RpcConnectionFactory {

    private Logger logger = LoggerFactory.getLogger(BleRpcConnectionFactory.class.getSimpleName());

    public static final int DISCOVERY_TIMEOUT  = 10 * 1000; // 10 seconds

    private Context context;
    private BluetoothAdapter adapter;
    private BluetoothGatt gattConnection;
    private boolean delimited;

    private UUID serviceUUID;
    private String targetMacAddress;
    private String targetBluetoothName;

    public void setTargetMacAddress(String targetMacAddress) {
        logger.warn("target mac address={}", targetMacAddress);
        this.targetMacAddress = targetMacAddress;
    }

    public void setTargetBluetoothName(String targetBluetoothName) {
        logger.warn("target bluetooth name={}", targetBluetoothName);
        this.targetBluetoothName = targetBluetoothName;
    }

    private UUID readCharUUID;
    private UUID writeCharUUID;

    private BluetoothGattCharacteristic readChar;
    private BluetoothGattCharacteristic writeChar;

    private int discoveryTimeout = DISCOVERY_TIMEOUT;

    public int getDiscoveryTimeout() {
        return discoveryTimeout;
    }

    public void setDiscoveryTimeout(int discoveryTimeout) {
        this.discoveryTimeout = discoveryTimeout;
    }

    private volatile boolean serverDiscovered = false;
    private AtomicBoolean connected = new AtomicBoolean(false);

    public BleRpcConnectionFactory(Context context,
                                   String serviceUUID,
                                   String targetMacAddress,
                                   String targetBluetoothName,
                                   String readCharUUID,
                                   String writeCharUUID,
                                   boolean delimited) {
        this.context = context;
        this.discoveryHandler = new Handler(context.getMainLooper());
        adapter = BluetoothAdapter.getDefaultAdapter();

        this.serviceUUID = UUID.fromString(serviceUUID);
        this.readCharUUID = UUID.fromString(readCharUUID);
        this.writeCharUUID = UUID.fromString(writeCharUUID);
        this.delimited = delimited;

        setTargetMacAddress(targetMacAddress);
        setTargetBluetoothName(targetBluetoothName);
    }

    public BleRpcConnectionFactory(Context context, String serviceUUID, String readCharUUID, String writeCharUUID, boolean delimited) {
        this(context, serviceUUID, null, null, readCharUUID, writeCharUUID, delimited);
    }

    public void _onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        logger.warn("Connection state changed from {} to {}", status, newState);

        if (newState == BluetoothProfile.STATE_CONNECTED) {
            gatt.discoverServices();
        }
    }

    @Override
    public void onConnectionStateChange(final BluetoothGatt gatt, final int status, final int newState) {
        logger.warn("onConnectionStateChange()");

        new Thread(new Runnable() {
            @Override
            public void run() {
                _onConnectionStateChange(gatt, status, newState);
            }
        }).start();
    }

    public void _onServicesDiscovered(BluetoothGatt gatt, int status) {
        logger.warn("_onServicesDiscovered: status={}", status);

        for (BluetoothGattService eachService : gatt.getServices())
            if (eachService.getUuid().equals(serviceUUID)) {
                // find characteristics
                for (BluetoothGattCharacteristic eachCharacteristic : eachService.getCharacteristics()) {
                    if (eachCharacteristic.getUuid().equals(readCharUUID)) {
                        logger.warn("Found read char");
                        readChar = eachCharacteristic; // change subscription is done in BleConnection
                    }

                    if (eachCharacteristic.getUuid().equals(writeCharUUID)) {
                        logger.warn("Found write char");
                        writeChar = eachCharacteristic;
                    }
                }
            }

        if (readChar == null || writeChar == null) {
            logger.error("Failed to find read/write char, disconnecting");

            gattConnection.disconnect();
            gattConnection = null;
            connectionThrowable = new Throwable("Service or read/write characteristics not found");
            connected.set(true); // just to unblock thread
            return;
        }

        try {
            logger.warn("Creating new connection");
            connection = new BleConnection(gattConnection, writeChar, readChar, delimited);
        } catch (IOException e) {
            logger.warn("Failed to create new connection", e);
            gattConnection.disconnect();
            gattConnection = null;
            connectionThrowable = e;
            connected.set(true); // just to unblock thread
            return;
        }
        logger.warn("Subscribing");
        connection.subscribe(); // blocks thread

        connected.set(true); // signal to return connection
    }

    @Override
    public void onServicesDiscovered(final BluetoothGatt gatt, final int status) {
        logger.warn("onServicesDiscovered()");

        new Thread(new Runnable() {
            @Override
            public void run() {
                _onServicesDiscovered(gatt, status);
            }
        }).start();
    }

    public void _onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
        logger.warn("_onDescriptorWrite(status={})", status);
        connection.notifyDescriptorWritten(descriptor);
    }

    @Override
    public void onDescriptorWrite(final BluetoothGatt gatt, final BluetoothGattDescriptor descriptor, final int status) {
        logger.warn("onDescriptorWrite()");

        new Thread(new Runnable() {
            @Override
            public void run() {
                _onDescriptorWrite(gatt, descriptor, status);
            }
        }).start();
    }

    @Override
    public void onCharacteristicWrite(final BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic, final int status) {
        logger.warn("onCharacteristicWrite()");

        new Thread(new Runnable() {
            @Override
            public void run() {
                if (status == BluetoothGatt.GATT_SUCCESS)
                    connection.onCharacteristicWrite(characteristic);
            }
        }).start();
    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        logger.warn("onCharacteristicChanged()");

        // WARNING: not in new background thread (in contrast to other on.. events) !
        connection.onCharacteristicChanged(characteristic);
    }

    private DiscoveryListener createConnectionListener = new DiscoveryListener() {
        @Override
        public void onStarted() {
            // not needed
        }

        @Override
        public void onBleDeviceDiscovered(BluetoothDevice device, int rssi, String scanDeviceName) {
            logger.warn("Device found: name={} ({}), mac_address={}, other={}", device.getName(), scanDeviceName, device.getAddress(), device);

            // check already connected
            if (serverDiscovered) {
                logger.warn("Already discovered, skipping device");
                return;
            }

            // if it's not our target device using mac address
            if (targetMacAddress != null && !device.getAddress().equals(targetMacAddress)) {
                logger.warn("not our device using mac address");
                return;
            }

            // if it's not our target device using ble name
            if (targetBluetoothName != null && !targetBluetoothName.equals(scanDeviceName)) {
                logger.warn("not our device using bluetooth name");
                return;
            }

            logger.warn("Found and accepted BLE device: {}", device);

            serverDiscovered = true;

            logger.warn("Stopping discovery");
            bleApi.stopDiscovery();

            logger.warn("Connecting to device");
            gattConnection = device.connectGatt(context, false, BleRpcConnectionFactory.this);
            gattConnection.connect();
        }

        @Override
        public void onFinished() {
            // not needed
        }
    };

    private BleConnection connection;
    private Throwable connectionThrowable;

    /**
     * Discovery listener
     */
    public interface DiscoveryListener {
        void onStarted();
        void onBleDeviceDiscovered(BluetoothDevice device, int rssi, String deviceName);
        void onFinished();
    }

    /**
     * BLE API
     */
    private interface IBleApi {
        void startDiscovery();
        void stopDiscovery();
    }

    /**
     * BLE API until 21
     */
    private class BleApi_Pre21 implements IBleApi, BluetoothAdapter.LeScanCallback {

        private DiscoveryListener listener;

        public BleApi_Pre21(DiscoveryListener listener) {
            this.listener = listener;
        }

        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
            String scanDeviceName = null;
            try {
                // parse using our own helper class
                final BleAdvertisedData scanData = BleHelper.parseAdertisedData(scanRecord);
                scanDeviceName = scanData.getName();
            } catch (Throwable t) {
                logger.error("Failed to parse scan data", t);
            }

            listener.onBleDeviceDiscovered(device, rssi, scanDeviceName);
        }

        @Override
        public void startDiscovery() {
            adapter.startLeScan(new UUID[]{ serviceUUID }, this);
        }

        @Override
        public void stopDiscovery() {
            adapter.stopLeScan(this);
        }
    }

    /**
     * BLE API 21+
     */
    private class BleApi_21 extends ScanCallback implements IBleApi {

        private DiscoveryListener listener;

        public BleApi_21(DiscoveryListener listener) {
            this.listener = listener;
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            if (results != null)
                for (ScanResult eachResult : results)
                    handleScanResult(eachResult);
        }

        @Override
        public void onScanFailed(int errorCode) {
            logger.error("BLE SCAN FAILED: {}", errorCode);
        }

        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handleScanResult(result);
        }

        private void handleScanResult(ScanResult result) {
            logger.warn("ScanResult: {}", result);

            // some devices does not support it, so we have to filter ourselves
            if (!adapter.isOffloadedFilteringSupported()) {
                logger.warn("Software service UUID filtering");
                if (result.getScanRecord().getServiceUuids() != null) {

                    boolean foundService = false;
                    for (ParcelUuid eachServiceUuid : result.getScanRecord().getServiceUuids()) {
                        logger.warn("Checking {}", eachServiceUuid);
                        if (eachServiceUuid.getUuid().equals(serviceUUID)) {
                            // found required service
                            logger.warn("Found required service UUID, invoking callback");
                            foundService = true;
                            break;
                        }
                    }

                    if (!foundService) {
                        logger.warn("No required service UUID found in declared services, exiting");
                        return;
                    }
                } else {
                    logger.warn("No services declared in scan record, exiting");
                    return;
                }
            } else {
                logger.warn("Using built-in service UUID filtering");
            }

            // on api 21 and later we're having scan name from android (result.getScanRecord().getDeviceName())
            listener.onBleDeviceDiscovered(result.getDevice(), result.getRssi(), result.getScanRecord().getDeviceName());
        }

        @Override
        public void startDiscovery() {
            // API 21
            List<ScanFilter> filters = new ArrayList<ScanFilter>();
            ScanFilter.Builder filterBuilder = new ScanFilter.Builder();

            // can be not supported (filtering in onScanResult)
            if (!adapter.isOffloadedFilteringSupported()) {
                logger.warn("OffloadedFiltering is not supported, filtering scan results later");
            } else {
                filterBuilder.setServiceUuid(new ParcelUuid(serviceUUID));
            }

            if (targetBluetoothName != null)
                filterBuilder.setDeviceName(targetBluetoothName);

            if (targetMacAddress != null)
                filterBuilder.setDeviceAddress(targetMacAddress);

            filters.add(filterBuilder.build());

            ScanSettings.Builder scanBuilder = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY);

            // can be not supported
            if (!adapter.isOffloadedScanBatchingSupported()) {
                logger.warn("OffloadedScanBatching is not supported");
            } else {
                scanBuilder.setReportDelay(discoveryDelay);  // 0 for immediate callback (not working for me), > 0 for batch mode
            }

            ScanSettings scanSettings = scanBuilder.build();

            BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                if (!adapter.isEnabled())
                    adapter.enable();

                long started = System.currentTimeMillis();

                // wait for adapter to be enabled
                while ((scanner = adapter.getBluetoothLeScanner()) == null) {
                    try { Thread.sleep(50); } catch (InterruptedException e) {}

                    if ((System.currentTimeMillis() - started) > 5000)
                        throw new RuntimeException("Failed to get LE scanner");
                }
            }
            scanner.startScan(filters, scanSettings, this);
        }

        @Override
        public void stopDiscovery() {
            adapter.getBluetoothLeScanner().stopScan(this);
        }
    }

    private boolean useAPI21 = false;

    public boolean isUseAPI21() {
        return useAPI21;
    }

    public void setUseAPI21(boolean useAPI21) {
        this.useAPI21 = useAPI21;
    }

    private Handler discoveryHandler;

    private int discoveryDelay = 0;

    public int getDiscoveryDelay() {
        return discoveryDelay;
    }

    /**
     * If using API 21 mode
     * @param discoveryDelay
     */
    public void setDiscoveryDelay(int discoveryDelay) {
        this.discoveryDelay = discoveryDelay;
    }

    private IBleApi bleApi;

    public void discover(final DiscoveryListener userDiscoveryListener) {
        this.serverDiscovered = false;

        // turn BLE on
        if (!adapter.isEnabled()) {
            logger.warn("Enabling BLE adapter");
            adapter.enable();
        }

        // started
        userDiscoveryListener.onStarted();
        logger.warn("Starting discovery");

        bleApi = (useAPI21
            ? new BleApi_21(userDiscoveryListener)
            : new BleApi_Pre21(userDiscoveryListener));
        bleApi.startDiscovery();

        // schedule discovery timeout
        discoveryHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                logger.warn("Discovery timeout fired ({})", discoveryTimeout);
                bleApi.stopDiscovery();

                // finished
                userDiscoveryListener.onFinished();
            }
        }, discoveryTimeout);
    }

    @Override
    public Connection createConnection() throws IOException {
        logger.warn("createConnection()");

        this.serverDiscovered = false;
        gattConnection = null;

        // create connection every time it's required
        connectionThrowable = null;
        connected.set(false);

        // turn BLE on
        if (!adapter.isEnabled()) {
            logger.warn("Enabling BLE adapter");
            adapter.enable();
        }

        bleApi = (useAPI21
            ? new BleApi_21(createConnectionListener)
            : new BleApi_Pre21(createConnectionListener));

        // start connection
        long discoveryStarted = System.currentTimeMillis();
        logger.warn("Start discovery at {}", discoveryStarted);
        bleApi.startDiscovery();

        // wait for connected
        while (!connected.get()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
            }

            // check timeout
            if ((System.currentTimeMillis() - discoveryStarted) > discoveryTimeout) {
                logger.warn("Discovery timeout exceeded ({})", discoveryTimeout);

                bleApi.stopDiscovery();

                // we can be connected to the device but we can have no services returned (timeout)
                if (gattConnection != null) {
                    gattConnection.disconnect();
                    gattConnection = null;
                }

                throw new DiscoveryTimeoutException(targetMacAddress, targetBluetoothName, discoveryTimeout);
            }
        }

        if (connectionThrowable != null) {
            logger.error("connection throwable", connectionThrowable);

            connected.set(false);
            throw new RuntimeException(connectionThrowable);
        }

        return connection;
    }

    /**
     * Throws when not discovered within Timeout
     */
    public static class DiscoveryTimeoutException extends IOException {

        private String macAddress;
        private String bleName;
        private int timeOut;

        public String getMacAddress() {
            return macAddress;
        }

        public String getBleName() {
            return bleName;
        }

        public int getTimeOut() {
            return timeOut;
        }

        public DiscoveryTimeoutException(String macAddress, String bleName, int timeOut) {
            this.macAddress = macAddress;
            this.bleName = bleName;
            this.timeOut = timeOut;
        }

        @Override
        public String getMessage() {
            return MessageFormat.format("Discovery timeout exception: mac={0}, name={1}, timeout={2} ms", macAddress, bleName, timeOut);
        }
    }
}
