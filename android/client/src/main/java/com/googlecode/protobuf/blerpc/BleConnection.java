package com.googlecode.protobuf.blerpc;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.util.Log;
import com.google.protobuf.MessageLite;
import com.googlecode.protobuf.socketrpc.RpcConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Connection for BLE (central role)
 */
public class BleConnection implements RpcConnectionFactory.Connection {

    private final String TAG = BleRpcConnectionFactory.class.getSimpleName();

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
    private AtomicBoolean unsubscribed = new AtomicBoolean(false);
    private BluetoothGattDescriptor readDescriptor;

    public boolean subscribe() {
        Log.w(TAG, "Subscribing ...");
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

        subscribed.set(false);
        // making few attempts to subscribe
        for (int i=0; i<3; i++) {
            if (connection.writeDescriptor(readDescriptor)) { // post value to remote
                Log.w(TAG, "Descriptor written (subscribe)");
                break;
            }

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

        Log.w(TAG, "Subscribed successfully");

        return true;
    }

    public void notifyDescriptorWritten(BluetoothGattDescriptor descriptor) {
        if (descriptor == readDescriptor) {
            if (Arrays.equals(descriptor.getValue(), BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                Arrays.equals(descriptor.getValue(), BluetoothGattDescriptor.ENABLE_INDICATION_VALUE))
                subscribed.set(true);
            else if (Arrays.equals(descriptor.getValue(), BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE))
                unsubscribed.set(true);
        }
    }

    @Override
    public void sendProtoMessage(MessageLite message) throws IOException {
//        Log.w(TAG, "Sending proto: " + message);

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

        try {
//            Log.w(TAG, "Received proto: " + messageBuilder.build());
        } catch (Exception e) {
            Log.e(TAG, "Failed to build proto", e);
        }
    }

    private boolean closed = false;

    @Override
    public void close() throws IOException {
        Log.w(TAG, "start closing connection");

        // unsubscribe
        connection.setCharacteristicNotification(readChar, false);

        // unsubscribe manually
        readDescriptor = readChar.getDescriptor(UUID.fromString(SUBSCRIBE_DESCRIPTOR_UUID));
        readDescriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE); // local value

        // making few attempts to unsubscribe
        unsubscribed.set(false);
        for (int i=0; i<3; i++) {
            if (connection.writeDescriptor(readDescriptor)) { // post value to remote
                Log.w(TAG, "Descriptor written (unsubscribe)");
                break;
            }

            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
            }
        }

        // wait for unsubscribed to read characteristic
        while (!unsubscribed.get()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        Log.w(TAG, "Unsubscribed successfully");

        in.close();
        out.close();

        // close BLE connection
        Log.w(TAG, "disconnecting");
        connection.disconnect();

        // wait until actually disconnected
        int  disconnectWaited = 0;
        while (!disconnected.get()) {
            try {
                Thread.sleep(100);
                disconnectWaited += 100;

                if (disconnectWaited > 5000) {
                    Log.w(TAG, "Waited for too long until actually disconnected");
                    break;
                }

            } catch (InterruptedException e) {
            }
        }

        Log.w(TAG, "closing connection");
        connection.close();

        closed = true;
    }

    private AtomicBoolean disconnected = new AtomicBoolean(false);

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

    public void notifyDisconnected() {
        disconnected.set(true);
    }
}
