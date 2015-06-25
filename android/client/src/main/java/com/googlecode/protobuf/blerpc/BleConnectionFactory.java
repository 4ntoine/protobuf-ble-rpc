package com.googlecode.protobuf.blerpc;

import android.bluetooth.*;
import android.content.Context;
import com.googlecode.protobuf.socketrpc.RpcConnectionFactory;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 *
 */
public class BleConnectionFactory extends BluetoothGattCallback implements RpcConnectionFactory {

    private Context context;
    private BluetoothAdapter adapter;
    private BluetoothGatt gattConnection;
    private boolean delimited;

    private UUID serviceUUID;
    private UUID readCharUUID;
    private UUID writeCharUUID;

    private BluetoothGattCharacteristic readChar;
    private BluetoothGattCharacteristic writeChar;

    private volatile boolean serverDiscovered = false;
    private AtomicBoolean connected = new AtomicBoolean(false);

    public BleConnectionFactory(Context context, String serviceUUID, String readCharUUID, String writeCharUUID, boolean delimited) {
        this.context = context;
        adapter = BluetoothAdapter.getDefaultAdapter();

        this.serviceUUID = UUID.fromString(serviceUUID);
        this.readCharUUID = UUID.fromString(readCharUUID);
        this.writeCharUUID = UUID.fromString(writeCharUUID);
        this.delimited = delimited;
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

    private BluetoothAdapter.LeScanCallback scanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
            if (serverDiscovered)
                return;

            serverDiscovered = true;
            adapter.stopLeScan(scanCallback);

            gattConnection = device.connectGatt(context, false, BleConnectionFactory.this);
            gattConnection.connect();
        }
    };

    private BleConnection connection;
    private Throwable connectionThrowable;

    @Override
    public Connection createConnection() throws IOException {
        if (connection == null) {
            connectionThrowable = null;
            connected.set(false);

            // start connection
            adapter.startLeScan(new UUID[]{ serviceUUID }, scanCallback);

            // wait for connected
            while (!connected.get())
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                }

            if (connectionThrowable != null) {
                connected.set(false);
                throw new RuntimeException(connectionThrowable);
            }
        }

        return connection;
    }
}
