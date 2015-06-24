package com.googlecode.protobuf.socketrpc;

import android.bluetooth.BluetoothGattCharacteristic;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Output stream for BLE
 */
public abstract class BleOutputStream extends OutputStream {

    public static final int OUTPUT_BUFFER_SIZE = 10 * 1024; // 10 Kb

    private byte[] buffer;
    private int length;
    private AtomicInteger writtenLength = new AtomicInteger(0); // bytes written

    public int getBufferSize() {
        return buffer.length;
    }

    private BluetoothGattCharacteristic characteristic;

    public BluetoothGattCharacteristic getCharacteristic() {
        return characteristic;
    }

    public BleOutputStream(int bufferSize, BluetoothGattCharacteristic characteristic) {
        buffer = new byte[bufferSize];
        _reset(true);

        this.characteristic = characteristic;
    }

    private void _reset(boolean fullReset) {
        length = 0;

        if (fullReset)
            writtenLength.set(0);
        lastPacket = null;
        length = 0;
    }

    private AtomicBoolean writing = new AtomicBoolean(false);

    /**
     * To be invoked from outside to notify new packet is sent over BLE
     */
    public void notifyWritten() {
        if (!writing.get())
            return;

        if (length == writtenLength.addAndGet(lastPacket.length)) {
            finishWriting();
        } else {
            _writePacket();
        }
    }

    private void finishWriting() {
        _reset(false);
        writing.set(false);
    }

    private byte[] lastPacket;

    // send new packet
    private boolean _writePacket() {
        int remainingLength = length - writtenLength.get();
        if (remainingLength == 0) {
            finishWriting();
            return true;
        }

        // having bytes to send

        // prepare packet
        int packetLength = Math.min(20, remainingLength);
        lastPacket = new byte[packetLength];
        System.arraycopy(buffer, writtenLength.get(), lastPacket, 0, packetLength);

        // send packet
        characteristic.setValue(lastPacket);
        return writeCharacteristic(characteristic);
    }

    protected abstract boolean writeCharacteristic(BluetoothGattCharacteristic characteristic);

    private void waitWritten(int length) {
        // notifyWritten() should be invoked from outside to check everything is sent
        while (writtenLength.get() < length) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
            }
        }
    }

    @Override
    public void write(int i) throws IOException {
        if (closed)
            return;

        if (writing.get())
            throw new IllegalStateException("Already writing");

        // prepare buffer for writing
        writing.set(true);
        buffer[length++] = (byte)i;

        _writePacket();
        waitWritten(1);  // blocks until actually written
    }

    @Override
    public void write(byte[] output) throws IOException {
        write(output, 0, output.length);
    }

    @Override
    public void write(byte[] output, int offset, int outputLength) throws IOException {
        if (closed)
            return;

        if (writing.get())
            throw new IllegalStateException("Already writing");

        // prepare buffer for writing
        writing.set(true);
        System.arraycopy(output, offset, buffer, writtenLength.get(), outputLength);
        length += outputLength;

        if (!_writePacket())
            throw new IOException("Failed to write BLE characteristic");
        waitWritten(outputLength); // blocks until actually written
        _reset(true);
    }

    private boolean closed = false;

    @Override
    public void close() throws IOException {
        super.close();

        closed = true;
        _reset(true);
        buffer = null;
    }
}
