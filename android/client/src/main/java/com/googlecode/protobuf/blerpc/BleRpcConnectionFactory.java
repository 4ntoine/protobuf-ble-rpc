package com.googlecode.protobuf.blerpc;

import android.bluetooth.*;
import android.content.Context;
import android.os.Handler;
import com.googlecode.protobuf.socketrpc.RpcConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.text.MessageFormat;
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

    private BluetoothAdapter.LeScanCallback connectScanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
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
    };

    private BleConnection connection;
    private Throwable connectionThrowable;

    /**
     * Discovery listener
     */
    public interface DiscoveryListener extends BluetoothAdapter.LeScanCallback {
        void onStarted();
        void onFinished();
    }

    private DiscoveryListener discoveryListener;
    private Handler discoveryHandler;

    public void discover(DiscoveryListener discoveryListener) {
        this.discoveryListener = discoveryListener;
        this.serverDiscovered = false;

        // turn BLE on
        if (!adapter.isEnabled()) {
            logger.debug("Enabling BLE adapter");
            adapter.enable();
        }

        // started
        this.discoveryListener.onStarted();
        logger.debug("Starting discovery");
        adapter.startLeScan(new UUID[]{ serviceUUID }, this.discoveryListener);

        // schedule discovery timeout
        discoveryHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                logger.warn("Discovery timeout fired ({})", discoveryTimeout);

                adapter.stopLeScan(BleRpcConnectionFactory.this.discoveryListener);

                // finished
                BleRpcConnectionFactory.this.discoveryListener.onFinished();
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

        // start connection
        long discoveryStarted = System.currentTimeMillis();

        logger.debug("Start discovery at {}", discoveryStarted);
        adapter.startLeScan(new UUID[]{ serviceUUID }, connectScanCallback);

        // wait for connected
        while (!connected.get()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
            }

            // check timeout
            if ((System.currentTimeMillis() - discoveryStarted) > discoveryTimeout) {
                logger.warn("Discovery timeout exceeded ({})", discoveryTimeout);

                adapter.stopLeScan(connectScanCallback);
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
