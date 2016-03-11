package com.googlecode.protobuf.blerpc;

import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Log;
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

    private static final String TAG = BleRpcConnectionFactory.class.getSimpleName();
//    private Logger logger = LoggerFactory.getLogger(BleRpcConnectionFactory.class.getSimpleName());

    public static final int DISCOVERY_TIMEOUT  = 10 * 1000; // 10 seconds

    private Context context;
    private BluetoothAdapter adapter;
    private BluetoothGatt gattConnection;
    private boolean delimited;

    private UUID serviceUUID;
    private String targetMacAddress;
    private String targetBluetoothName;

    public void setTargetMacAddress(String targetMacAddress) {
        Log.d(TAG, "target mac address=" + targetMacAddress);
        this.targetMacAddress = targetMacAddress;
    }

    public void setTargetBluetoothName(String targetBluetoothName) {
        Log.d(TAG, "target bluetooth name=" + targetBluetoothName);
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
    private AtomicBoolean connecting = new AtomicBoolean(false);

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

    public void _onConnectionStateChange(BluetoothGatt gatt, int state, int newState) {
        Log.d(TAG, "Connection state changed from " + state + " to " + newState);

        if (newState == BluetoothProfile.STATE_CONNECTED) {

            if (connectionThrowable != null) {
                Log.w(TAG, "Eventually connected after 133 -> 0 error, clearing error");
                connectionThrowable = null;
            }

            Log.d(TAG, "Start discovering services");
            gatt.discoverServices();
        }

        if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            Log.d(TAG, "Disconnected");
            if (connection != null)
                connection.notifyDisconnected();

            if (connecting.get() &&
                (
                    state == 133    // GATT_ERROR
                 || state == 62
                 || state == 129    // android 4.x
                )
               )
            {
                Log.e(TAG, "Fatal BLE connect error");
                connectionThrowable = new FailedToConnectException(state);

                // signal to unlock for waiting in createConnection()
                if (connecting.get())
                    connected.set(true);
            }

            connection = null;
        }
    }

    @Override
    public void onConnectionStateChange(final BluetoothGatt gatt, final int state, final int newState) {
        Log.d(TAG, "onConnectionStateChange(state=" + state + ", newState=" + newState + ")");

        new Thread(new Runnable() {
            @Override
            public void run() {
                _onConnectionStateChange(gatt, state, newState);
            }
        }).start();
    }

    public void _onServicesDiscovered(BluetoothGatt gatt, int state) {
        Log.d(TAG, "_onServicesDiscovered: state=" + state);

        for (BluetoothGattService eachService : gatt.getServices())
            if (eachService.getUuid().equals(serviceUUID)) {
                // find characteristics
                for (BluetoothGattCharacteristic eachCharacteristic : eachService.getCharacteristics()) {
                    if (eachCharacteristic.getUuid().equals(readCharUUID)) {
                        Log.w(TAG, "Found read char");
                        readChar = eachCharacteristic; // change subscription is done in BleConnection
                    }

                    if (eachCharacteristic.getUuid().equals(writeCharUUID)) {
                        Log.d(TAG, "Found write char");
                        writeChar = eachCharacteristic;
                    }
                }
            }

        if (readChar == null || writeChar == null) {
            Log.e(TAG, "Failed to find read/write char, disconnecting");

            gattConnection.disconnect();
            gattConnection.close();
            gattConnection = null;
            connectionThrowable = new NoCharacteristicsException();
            connected.set(true); // just to unblock thread
            return;
        }

        try {
            Log.d(TAG, "Creating new connection");
            connection = new BleConnection(gattConnection, writeChar, readChar, delimited);
        } catch (IOException e) {
            Log.w(TAG, "Failed to create new connection", e);
            gattConnection.disconnect();
            gattConnection.close();
            gattConnection = null;
            connectionThrowable = e;
            connected.set(true); // just to unblock thread
            return;
        }
        Log.w(TAG, "Subscribing");
        connection.subscribe(); // blocks thread

        connected.set(true); // signal to return connection
    }

    @Override
    public void onServicesDiscovered(final BluetoothGatt gatt, final int status) {
        Log.w(TAG, "onServicesDiscovered()");

        new Thread(new Runnable() {
            @Override
            public void run() {
                _onServicesDiscovered(gatt, status);
            }
        }).start();
    }

    public void _onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
        Log.w(TAG, "_onDescriptorWrite(status=" + status + ")");
        connection.notifyDescriptorWritten(descriptor);
    }

    @Override
    public void onDescriptorWrite(final BluetoothGatt gatt, final BluetoothGattDescriptor descriptor, final int status) {
        Log.w(TAG, "onDescriptorWrite()");

        new Thread(new Runnable() {
            @Override
            public void run() {
                _onDescriptorWrite(gatt, descriptor, status);
            }
        }).start();
    }

    @Override
    public void onCharacteristicWrite(final BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic, final int status) {
        Log.w(TAG, "onCharacteristicWrite()");

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
        Log.w(TAG, "onCharacteristicChanged()");

        // WARNING: not in new background thread (in contrast to other on.. events) !
        connection.onCharacteristicChanged(characteristic);
    }

    private Integer afterDiscoverSleepMs = new Integer(100); // ms

    private DiscoveryListener createConnectionListener = new DiscoveryListener() {
        @Override
        public void onStarted() {
            // not needed
        }

        @Override
        public void onBleDeviceDiscovered(BluetoothDevice device, int rssi, String scanDeviceName) {
            Log.w(TAG, "Device found: name=" + device.getName() + " (" + scanDeviceName + "), mac_address=" + device.getAddress() + ", other=" + device);

            // check already connected
            if (serverDiscovered) {
                Log.w(TAG, "Already discovered, skipping device");
                return;
            }

            // if it's not our target device using mac address
            if (targetMacAddress != null && !device.getAddress().equals(targetMacAddress)) {
                Log.w(TAG, "not our device using mac address");
                return;
            }

            // if it's not our target device using ble name
            if (targetBluetoothName != null && !targetBluetoothName.equals(scanDeviceName)) {
                Log.w(TAG, "not our device using bluetooth name");
                return;
            }

            Log.w(TAG, "Found and accepted BLE device: " + device);

            serverDiscovered = true;

            Log.w(TAG, "Stopping discovery");
            bleApi.stopDiscovery();

            if (afterDiscoverSleepMs != null) {
                Log.w(TAG, "Sleeping after discovered (ms): " + afterDiscoverSleepMs);
                try {
                    Thread.sleep(afterDiscoverSleepMs);
                } catch (InterruptedException e) {
                }
            }

            Log.w(TAG, "Connecting to device");
            gattConnection = device.connectGatt(context, false, BleRpcConnectionFactory.this);
        }

        @Override
        public void onFinished() {
            // not needed
        }
    };

    private BleConnection connection;
    private IOException connectionThrowable;

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
        void startDiscovery() throws IOException;
        boolean isDiscovering();
        void stopDiscovery();
    }

    /**
     * BLE API until 21
     */
    private class BleApi_Pre21 implements IBleApi, BluetoothAdapter.LeScanCallback {

        private DiscoveryListener listener;
        private AtomicBoolean isDiscovering = new AtomicBoolean(false);

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
                Log.e(TAG, "Failed to parse scan data", t);
            }

            Log.d(TAG, "Device found: " + device.getName() + " (" + scanDeviceName + ")");
            listener.onBleDeviceDiscovered(device, rssi, scanDeviceName);
        }

        @Override
        public void startDiscovery() {
            isDiscovering.set(true);
            adapter.startLeScan(new UUID[]{ serviceUUID }, this);
        }

        @Override
        public boolean isDiscovering() {
            return isDiscovering.get();
        }

        @Override
        public void stopDiscovery() {
            isDiscovering.set(false);
            adapter.stopLeScan(this);
        }
    }

    public static final int GET_SCANNER_TIMEOUT = 5000; // 5s

    private int getScannerTimeout = GET_SCANNER_TIMEOUT;

    public int getGetScannerTimeout() {
        return getScannerTimeout;
    }

    public void setGetScannerTimeout(int getScannerTimeout) {
        this.getScannerTimeout = getScannerTimeout;
    }

    /**
     * BLE API 21+
     */
    private class BleApi_21 extends ScanCallback implements IBleApi {

        private DiscoveryListener listener;
        private AtomicBoolean isDiscovering = new AtomicBoolean(false);

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
            Log.w(TAG, "BLE SCAN FAILED: " + errorCode);
        }

        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handleScanResult(result);
        }

        private void handleScanResult(ScanResult result) {
            Log.w(TAG, "ScanResult: " + result);

            // some devices does not support it, so we have to filter ourselves
            if (!adapter.isOffloadedFilteringSupported()) {
                Log.w(TAG, "Software service UUID filtering");
                if (result.getScanRecord().getServiceUuids() != null) {

                    boolean foundService = false;
                    for (ParcelUuid eachServiceUuid : result.getScanRecord().getServiceUuids()) {
                        Log.w(TAG, "Checking " + eachServiceUuid);
                        if (eachServiceUuid.getUuid().equals(serviceUUID)) {
                            // found required service
                            Log.w(TAG, "Found required service UUID, invoking callback");
                            foundService = true;
                            break;
                        }
                    }

                    if (!foundService) {
                        Log.w(TAG, "No required service UUID found in declared services, exiting");
                        return;
                    }
                } else {
                    Log.w(TAG, "No services declared in scan record, exiting");
                    return;
                }
            } else {
                Log.w(TAG, "Using built-in service UUID filtering");
            }

            // on api 21 and later we're having scan name from android (result.getScanRecord().getDeviceName())
            listener.onBleDeviceDiscovered(result.getDevice(), result.getRssi(), result.getScanRecord().getDeviceName());
        }

        @Override
        public void startDiscovery() throws IOException {
            // API 21
            List<ScanFilter> filters = new ArrayList<ScanFilter>();
            ScanFilter.Builder filterBuilder = new ScanFilter.Builder();

            // can be not supported (filtering in onScanResult)
            if (!adapter.isOffloadedFilteringSupported()) {
                Log.w(TAG, "OffloadedFiltering is not supported, filtering scan results later");
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
                Log.w(TAG, "OffloadedScanBatching is not supported");
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
                    try { Thread.sleep(50);} catch (InterruptedException e) {}

                    if ((System.currentTimeMillis() - started) > getScannerTimeout)
                        throw new NoLeScannerException();
                }
            }
            isDiscovering.set(true);
            scanner.startScan(filters, scanSettings, this);
        }

        @Override
        public boolean isDiscovering() {
            return isDiscovering.get();
        }

        @Override
        public void stopDiscovery() {
            isDiscovering.set(false);
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

    public void discover(final DiscoveryListener userDiscoveryListener) throws IOException {
        this.serverDiscovered = false;

        // turn BLE on
        if (!adapter.isEnabled()) {
            Log.w(TAG, "Enabling BLE adapter");
            adapter.enable();
        }

        // started
        userDiscoveryListener.onStarted();
        Log.w(TAG, "Starting discovery");

        bleApi = (useAPI21
            ? new BleApi_21(userDiscoveryListener)
            : new BleApi_Pre21(userDiscoveryListener));
        bleApi.startDiscovery();

        // schedule discovery timeout
        discoveryHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Log.w(TAG, "Discovery timeout fired : " + discoveryTimeout);
                bleApi.stopDiscovery();

                // finished
                userDiscoveryListener.onFinished();
            }
        }, discoveryTimeout);
    }

    @Override
    public Connection createConnection() throws IOException {
        Log.w(TAG, "createConnection()");

        this.serverDiscovered = false;
        gattConnection = null;
        connection = null;

        // create connection every time it's required
        connectionThrowable = null;
        connected.set(false);
        connecting.set(true);

        // turn BLE on
        if (!adapter.isEnabled()) {
            Log.w(TAG, "Enabling BLE adapter");
            adapter.enable();
        }

        bleApi = (useAPI21
            ? new BleApi_21(createConnectionListener)
            : new BleApi_Pre21(createConnectionListener));

        // start connection
        long discoveryStarted = System.currentTimeMillis();
        Log.w(TAG, "Start discovery");
        bleApi.startDiscovery();

        // wait for connected
        while (!connected.get()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
            }

            // check timeout
            if ((System.currentTimeMillis() - discoveryStarted) > discoveryTimeout) {
                Log.w(TAG, "Discovery timeout exceeded (" + discoveryTimeout + ")");

                if (bleApi.isDiscovering())
                    bleApi.stopDiscovery();

                // we can be connected to the device but we can have no services returned (timeout)
                if (gattConnection != null) {
                    gattConnection.disconnect();
                    gattConnection.close();
                    connecting.set(false);
                    connected.set(false);
                    gattConnection = null;
                }

                Log.e(TAG, "Throwing DiscoveryTimeoutException");
                throw new DiscoveryTimeoutException(targetMacAddress, targetBluetoothName, discoveryTimeout);
            }
        }

        if (connectionThrowable != null) {
            Log.e(TAG, "connection throwable", connectionThrowable);

            connected.set(false);
            connecting.set(false);
            Log.e(TAG, "NOT created connection in " + (System.currentTimeMillis() - discoveryStarted) + " ms");
            throw connectionThrowable;
        }

        Log.d(TAG, "Created connection in " + (System.currentTimeMillis() - discoveryStarted) + " ms");
        connecting.set(false);

        return connection;
    }

    /**
     * Thrown when error 133 is returned
     *
     */
    public static class FailedToConnectException extends IOException {

        private int state;

        public int getState() {
            return state;
        }

        public FailedToConnectException (int state) {
            this.state = state;
        }

        @Override
        public String getMessage() {
            return MessageFormat.format("BLE error state: {0}", state);
        }
    }

    /**
     * Thrown when no read/write characteristics is found
     */

    public static class NoCharacteristicsException extends IOException {
    }

    /**
     * LE scanner is not available (null)
     */
    public static class NoLeScannerException extends IOException {

    }

    /**
     * Thrown when not discovered within Timeout
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
