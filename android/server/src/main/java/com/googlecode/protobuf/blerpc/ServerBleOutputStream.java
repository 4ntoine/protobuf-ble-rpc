package com.googlecode.protobuf.blerpc;

import android.bluetooth.BluetoothGattCharacteristic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Output stream for BLE (peripheral role)
 */
public class ServerBleOutputStream extends BleOutputStream {

    private ServerBleRpcConnectionFactory factory;

    public ServerBleOutputStream(int buffer_size, BluetoothGattCharacteristic characteristic, ServerBleRpcConnectionFactory factory) {
        super(buffer_size, characteristic);
        this.factory = factory;
    }

    @Override
    protected boolean writeCharacteristic(final BluetoothGattCharacteristic characteristic) {
        return factory.notifyChanged(characteristic);
    }
}
