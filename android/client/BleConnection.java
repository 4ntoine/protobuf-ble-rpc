package com.googlecode.protobuf.socketrpc;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import com.google.protobuf.MessageLite;

import java.io.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Connection for BLE (central role)
 */
public class BleConnection implements RpcConnectionFactory.Connection {

    private BluetoothGatt connection;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothGattCharacteristic readChar;

    private final boolean delimited;

    private BleInputStream in;
    private BleOutputStream out;

    private static final String SUBSCRIBE_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805f9b34fb"; // 0x2902

    public BleConnection(BluetoothGatt connection,
                        BluetoothGattCharacteristic writeChar,
                        BluetoothGattCharacteristic readChar,
                        boolean delimited) throws IOException {
        this.connection = connection;
        this.writeChar = writeChar;
        this.readChar = readChar;
        this.delimited = delimited;

        in = new BleInputStream(BleInputStream.INPUT_BUFFER_SIZE, BleInputStream.READ_TIMEOUT);
        out = new ClientBleOutputStream(BleOutputStream.OUTPUT_BUFFER_SIZE, writeChar, connection);
    }

    private AtomicBoolean subscribed = new AtomicBoolean(false);
    private BluetoothGattDescriptor readDescriptor;

    public boolean subscribe() {
        subscribed.set(false);

        // subscribe to read notifications
        connection.setCharacteristicNotification(readChar, true); // TODO: for some reason not working (need to subscribe manually)

        // WARNING: delay should be set (http://stackoverflow.com/questions/17910322/android-ble-api-gatt-notification-not-received)
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // subscribe manually
        readDescriptor = readChar.getDescriptor(UUID.fromString(SUBSCRIBE_DESCRIPTOR_UUID));
        readDescriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); // local value

        // making few attempts to subscribe
        for (int i=0; i<3; i++) {
            if (connection.writeDescriptor(readDescriptor)) // post value to remote
                break;

            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
            }
        }

        // wait for subscribed to read characteristic
        while (!subscribed.get()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        return true;
    }

    public void notifyDescriptorWritten(BluetoothGattDescriptor descriptor) {
        if (descriptor == readDescriptor)
            subscribed.set(true);
    }

    @Override
    public void sendProtoMessage(MessageLite message) throws IOException {
        // Write message
        if (delimited) {
            message.writeDelimitedTo(out);
            out.flush();
        } else {
            message.writeTo(out);
            out.flush();
        }
    }

    @Override
    public void receiveProtoMessage(MessageLite.Builder messageBuilder) throws IOException {
        // Read message
        if (delimited) {
            messageBuilder.mergeDelimitedFrom(in);
        } else {
            messageBuilder.mergeFrom(in);
        }
    }

    private boolean closed = false;

    @Override
    public void close() throws IOException {
        // unsubscribe
        connection.setCharacteristicNotification(readChar, false);

        in.close();
        out.close();

        closed = true;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    public void onCharacteristicWrite(BluetoothGattCharacteristic characteristic) {
        // packet is sent, need to notify output stream
        if (characteristic == writeChar)
            out.notifyWritten();
    }

    public void onCharacteristicChanged(BluetoothGattCharacteristic characteristic) {
        // incoming bytes arrive, need to notify input stream
        if (characteristic == readChar)
            in.doRead(characteristic.getValue());
    }
}
