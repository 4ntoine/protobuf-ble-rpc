package com.googlecode.protobuf.blerpc;

import android.bluetooth.*;
import android.content.Context;
import android.os.Handler;
import com.googlecode.protobuf.socketrpc.RpcConnectionFactory;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 *
 */
public class BleRpcConnectionFactory extends BluetoothGattCallback implements RpcConnectionFactory {

    public static final int DISCOVERY_TIMEOUT  = 10 * 1000; // 10 seconds

    private Context context;
    private BluetoothAdapter adapter;
    private BluetoothGatt gattConnection;
    private boolean delimited;

    private UUID serviceUUID;
    private BluetoothDevice bluetoothDevice;

    public void setBluetoothDevice(BluetoothDevice bluetoothDevice) {
        this.bluetoothDevice = bluetoothDevice;
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
                                   BluetoothDevice bluetoothDevice,
                                   String readCharUUID,
                                   String writeCharUUID,
                                   boolean delimited) {
        this.context = context;
        this.discoveryHandler = new Handler(context.getMainLooper());
        adapter = BluetoothAdapter.getDefaultAdapter();

        this.serviceUUID = UUID.fromString(serviceUUID);
        this.bluetoothDevice = bluetoothDevice;
        this.readCharUUID = UUID.fromString(readCharUUID);
        this.writeCharUUID = UUID.fromString(writeCharUUID);
        this.delimited = delimited;
    }

    public BleRpcConnectionFactory(Context context, String serviceUUID, String readCharUUID, String writeCharUUID, boolean delimited) {
        this(context, serviceUUID, null, readCharUUID, writeCharUUID, delimited);
    }

    public void _onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            gatt.discoverServices();
        }
    }

    @Override
    public void onConnectionStateChange(final BluetoothGatt gatt, final int status, final int newState) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                _onConnectionStateChange(gatt, status, newState);
            }
        }).start();
    }

    public void _onServicesDiscovered(BluetoothGatt gatt, int status) {
        for (BluetoothGattService eachService : gatt.getServices())
            if (eachService.getUuid().equals(serviceUUID)) {
                // find characteristics
                for (BluetoothGattCharacteristic eachCharacteristic : eachService.getCharacteristics()) {
                    if (eachCharacteristic.getUuid().equals(readCharUUID))
                        readChar = eachCharacteristic; // change subscription is done in BleConnection

                    if (eachCharacteristic.getUuid().equals(writeCharUUID))
                        writeChar = eachCharacteristic;
                }
            }

        if (readChar == null || writeChar == null) {
            gattConnection.disconnect();
            connectionThrowable = new Throwable("Service or read/write characteristics not found");
            connected.set(true); // just to unblock thread
            return;
        }

        try {
            connection = new BleConnection(gattConnection, writeChar, readChar, delimited);
        } catch (IOException e) {
            gattConnection.disconnect();
            connectionThrowable = e;
            connected.set(true); // just to unblock thread
            return;
        }
        connection.subscribe(); // blocks thread

        connected.set(true); // signal to return connection
    }

    @Override
    public void onServicesDiscovered(final BluetoothGatt gatt, final int status) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                _onServicesDiscovered(gatt, status);
            }
        }).start();
    }

    public void _onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
        connection.notifyDescriptorWritten(descriptor);
    }

    @Override
    public void onDescriptorWrite(final BluetoothGatt gatt, final BluetoothGattDescriptor descriptor, final int status) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                _onDescriptorWrite(gatt, descriptor, status);
            }
        }).start();
    }

    @Override
    public void onCharacteristicWrite(final BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic, final int status) {
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
        // WARNING: not in new background thread (in contrast to other on.. events) !
        connection.onCharacteristicChanged(characteristic);
    }

    private BluetoothAdapter.LeScanCallback connectScanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
            if (serverDiscovered    // already discovered
                    ||
                (bluetoothDevice != null && !bluetoothDevice.equals(device)))   // specific device required and it's not that device
                return;

            serverDiscovered = true;
            adapter.stopLeScan(connectScanCallback);

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
        if (!adapter.isEnabled())
            adapter.enable();

        // started
        this.discoveryListener.onStarted();
        adapter.startLeScan(new UUID[]{ serviceUUID }, this.discoveryListener);

        // schedule discovery timeout
        discoveryHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                adapter.stopLeScan(BleRpcConnectionFactory.this.discoveryListener);

                // finished
                BleRpcConnectionFactory.this.discoveryListener.onFinished();
            }
        }, discoveryTimeout);
    }

    @Override
    public Connection createConnection() throws IOException {
        this.serverDiscovered = false;

        if (connection == null) {
            connectionThrowable = null;
            connected.set(false);

            // turn BLE on
            if (!adapter.isEnabled())
                adapter.enable();

            // start connection
            long discoveryStarted = System.currentTimeMillis();

            adapter.startLeScan(new UUID[]{ serviceUUID }, connectScanCallback);

            // wait for connected
            while (!connected.get()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                }

                // check timeout
                if ((System.currentTimeMillis() - discoveryStarted) > discoveryTimeout) {
                    adapter.stopLeScan(connectScanCallback);
                    throw new DiscoveryTimeoutException(bluetoothDevice, discoveryTimeout);
                }
            }

            if (connectionThrowable != null) {
                connected.set(false);
                throw new RuntimeException(connectionThrowable);
            }
        }

        return connection;
    }

    /**
     * Throws when not discovered within Timeout
     */
    public static class DiscoveryTimeoutException extends IOException {

        private BluetoothDevice bluetoothDevice;
        private int timeOut;

        public BluetoothDevice getBluetoothDevice() {
            return bluetoothDevice;
        }

        public int getTimeOut() {
            return timeOut;
        }

        public DiscoveryTimeoutException(BluetoothDevice bluetoothDevice, int timeOut) {
            this.bluetoothDevice = bluetoothDevice;
            this.timeOut = timeOut;
        }

        @Override
        public String getMessage() {
            return MessageFormat.format("Discovery timeout exception: device={0}, timeout={1} ms", bluetoothDevice, timeOut);
        }
    }
}
