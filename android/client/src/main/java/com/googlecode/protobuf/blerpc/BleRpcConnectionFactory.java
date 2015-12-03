package com.googlecode.protobuf.blerpc;

import android.bluetooth.*;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
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
 *
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
        logger.debug("target mac address={}", targetMacAddress);
        this.targetMacAddress = targetMacAddress;
    }

    public void setTargetBluetoothName(String targetBluetoothName) {
        logger.debug("target bluetooth name={}", targetBluetoothName);
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
        logger.debug("Connection state changed from {} to {}", status, newState);

        if (newState == BluetoothProfile.STATE_CONNECTED) {
            gatt.discoverServices();
        }
    }

    @Override
    public void onConnectionStateChange(final BluetoothGatt gatt, final int status, final int newState) {
        logger.debug("onConnectionStateChange()");

        new Thread(new Runnable() {
            @Override
            public void run() {
                _onConnectionStateChange(gatt, status, newState);
            }
        }).start();
    }

    public void _onServicesDiscovered(BluetoothGatt gatt, int status) {
        logger.debug("_onServicesDiscovered: status={}", status);

        for (BluetoothGattService eachService : gatt.getServices())
            if (eachService.getUuid().equals(serviceUUID)) {
                // find characteristics
                for (BluetoothGattCharacteristic eachCharacteristic : eachService.getCharacteristics()) {
                    if (eachCharacteristic.getUuid().equals(readCharUUID)) {
                        logger.debug("Found read char");
                        readChar = eachCharacteristic; // change subscription is done in BleConnection
                    }

                    if (eachCharacteristic.getUuid().equals(writeCharUUID)) {
                        logger.debug("Found write char");
                        writeChar = eachCharacteristic;
                    }
                }
            }

        if (readChar == null || writeChar == null) {
            logger.error("Failed to find read/write char, disconnecting");

            gattConnection.disconnect();
            connectionThrowable = new Throwable("Service or read/write characteristics not found");
            connected.set(true); // just to unblock thread
            return;
        }

        try {
            logger.debug("Creating new connection");
            connection = new BleConnection(gattConnection, writeChar, readChar, delimited);
        } catch (IOException e) {
            logger.debug("Failed to create new connection", e);
            gattConnection.disconnect();
            connectionThrowable = e;
            connected.set(true); // just to unblock thread
            return;
        }
        logger.debug("Subscribing");
        connection.subscribe(); // blocks thread

        connected.set(true); // signal to return connection
    }

    @Override
    public void onServicesDiscovered(final BluetoothGatt gatt, final int status) {
        logger.debug("onServicesDiscovered()");

        new Thread(new Runnable() {
            @Override
            public void run() {
                _onServicesDiscovered(gatt, status);
            }
        }).start();
    }

    public void _onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
        logger.debug("_onDescriptorWrite(status={})", status);
        connection.notifyDescriptorWritten(descriptor);
    }

    @Override
    public void onDescriptorWrite(final BluetoothGatt gatt, final BluetoothGattDescriptor descriptor, final int status) {
        logger.debug("onDescriptorWrite()");

        new Thread(new Runnable() {
            @Override
            public void run() {
                _onDescriptorWrite(gatt, descriptor, status);
            }
        }).start();
    }

    @Override
    public void onCharacteristicWrite(final BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic, final int status) {
        logger.debug("onCharacteristicWrite()");

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
        logger.debug("onCharacteristicChanged()");

        // WARNING: not in new background thread (in contrast to other on.. events) !
        connection.onCharacteristicChanged(characteristic);
    }

    private DiscoveryListener createConnectionListener = new DiscoveryListener() {
        @Override
        public void onStarted() {
            // not needed
        }

        @Override
        public void onBleDeviceDiscovered(BluetoothDevice device, int rssi) {
            logger.debug("Device found: name={}, mac_address={}, other={}", device.getName(), device.getAddress(), device);

            // check already connected
            if (serverDiscovered) {
                logger.debug("Already discovered, skipping device");
                return;
            }

            // if it's not our target device using mac address
            if (targetMacAddress != null && !device.getAddress().equals(targetMacAddress)) {
                logger.debug("not our device using mac address");
                return;
            }

            // if it's not our target device using ble name
            if (targetBluetoothName != null && !device.getName().equals(targetBluetoothName)) {
                logger.debug("not our device using bluetooth name");
                return;
            }

            logger.debug("Found and accepted BLE device: {}", device);

            serverDiscovered = true;

            logger.debug("Stopping discovery");
            adapter.stopLeScan(connectScanCallback);

            logger.debug("Connecting to device");
            gattConnection = device.connectGatt(context, false, BleRpcConnectionFactory.this);
            gattConnection.connect();
        }

        @Override
        public void onFinished() {
            // not needed
        }
    };

    private BluetoothAdapter.LeScanCallback connectScanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {

        }
    };

    private BleConnection connection;
    private Throwable connectionThrowable;

    /**
     * Discovery listener
     */
    public interface DiscoveryListener {
        void onStarted();
        void onBleDeviceDiscovered(BluetoothDevice device, int rssi);
        void onFinished();
    }

    /**
     * BLE API
     */
    private interface IBLEAPI {
        void startDiscovery();
        void stopDiscovery();
    }

    /**
     * BLE API until 21
     */
    private class BleApi_Pre21 implements IBLEAPI, BluetoothAdapter.LeScanCallback {

        private DiscoveryListener listener;

        public BleApi_Pre21(DiscoveryListener listener) {
            this.listener = listener;
        }

        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
            listener.onBleDeviceDiscovered(device, rssi);
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
    private class BleApi_21 extends ScanCallback implements IBLEAPI {

        private DiscoveryListener listener;

        public BleApi_21(DiscoveryListener listener) {
            this.listener = listener;
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
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
            listener.onBleDeviceDiscovered(result.getDevice(), result.getRssi());
        }

        @Override
        public void startDiscovery() {
            // API 21
            List<ScanFilter> filters = new ArrayList<ScanFilter>();

            ScanFilter.Builder filterBuilder = new ScanFilter
                .Builder()
                .setServiceUuid(new ParcelUuid(serviceUUID));

            if (targetBluetoothName != null)
                filterBuilder.setDeviceName(targetBluetoothName);

            if (targetMacAddress != null)
                filterBuilder.setDeviceAddress(targetMacAddress);

            filters.add(filterBuilder.build());

            ScanSettings scanSettings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(discoveryDelay)  // 0 for immediate callback (not working for me), > 0 for batch mode
                .build();

            adapter.getBluetoothLeScanner().startScan(filters, scanSettings, this);
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

    private IBLEAPI bleApi;

    public void discover(final DiscoveryListener userDiscoveryListener) {
        this.serverDiscovered = false;

        // turn BLE on
        if (!adapter.isEnabled()) {
            logger.debug("Enabling BLE adapter");
            adapter.enable();
        }

        // started
        userDiscoveryListener.onStarted();
        logger.debug("Starting discovery");

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
        logger.debug("createConnection()");

        this.serverDiscovered = false;

        // create connection every time it's required
        connectionThrowable = null;
        connected.set(false);

        // turn BLE on
        if (!adapter.isEnabled()) {
            logger.debug("Enabling BLE adapter");
            adapter.enable();
        }

        bleApi = (useAPI21
            ? new BleApi_21(createConnectionListener)
            : new BleApi_Pre21(createConnectionListener));

        // start connection
        long discoveryStarted = System.currentTimeMillis();
        logger.debug("Start discovery at {}", discoveryStarted);
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
