package com.googlecode.protobuf.blerpc;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;

/**
 * Output stream for BLE central role
 */
public class ClientBleOutputStream extends BleOutputStream {

    private BluetoothGatt connection;

    public ClientBleOutputStream(int buffer_size, BluetoothGattCharacteristic characteristic, BluetoothGatt connection) {
        super(buffer_size, characteristic);
        this.connection = connection;
    }

    private static final int WRITE_ATTEMPTS = 3;

    @Override
    protected boolean writeCharacteristic(BluetoothGattCharacteristic characteristic) {
        // trying to write few times
        for (int i=0; i<WRITE_ATTEMPTS; i++) {
            if (connection.writeCharacteristic(characteristic))
                return true;

            // sleep and do another attempt
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
            }
        }

        return false;
    }
}
