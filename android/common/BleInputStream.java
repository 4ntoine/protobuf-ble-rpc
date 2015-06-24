package com.googlecode.protobuf.socketrpc;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Input stream for BLE
 */
public class BleInputStream extends InputStream {

    public static final int INPUT_BUFFER_SIZE  = 10 * 1024; // 10 Kb
    public static final int READ_TIMEOUT = 5 * 60 * 1000; // 5 minutes (for debugging) !

    private byte[] buffer;
    private AtomicInteger lastIndex = new AtomicInteger(0); // last written byte index
    private int readIndex; // first byte index to read from
    private int readTimeout;

    public int getBufferSize() {
        return buffer.length;
    }

    private void _reset() {
        lastIndex.set(-1);
        readIndex = -1;
    }

    public BleInputStream(int bufferSize, int timeOut) {
        buffer = new byte[bufferSize];
        this.readTimeout = timeOut;
        _reset();
    }

    @Override
    public int available() throws IOException {
        return lastIndex.get() - readIndex;
    }

    @Override
    public synchronized void reset() throws IOException {
        _reset();
    }

    @Override
    public int read() throws IOException {
        Logger.get().log("BleInputStream.read()");

        if (closed) {
            Logger.get().log("BleInputStream: end of stream");
            return -1; // end of stream
        }

        // should block until 1 byte at least is received
        while (available() == 0 || closed) {
            if (closed)
                return -1; // end of stream

            // block thread
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
            }
        }

        int readByte = buffer[++readIndex];

        // check need reset indices
        checkReset();


        Logger.get().log("BleInputStream.read() finished");

        return readByte;
    }

    private void checkReset() throws IOException {
        if (available() == 0)
            _reset();
    }

    @Override
    public int read(byte[] output) throws IOException {
        return read(output, 0, output.length);
    }

    @Override
    public int read(byte[] output, int offset, int length) throws IOException {
        Logger.get().log("BleInputStream.read() requested length=" + length);

        if (closed)
            return -1; // end of stream

        long started = System.currentTimeMillis();

        // waiting stream to have requested length
        while (available() < length) {

            if ((System.currentTimeMillis() - started) > readTimeout)
                return -1; // end of stream

            try {
                Thread.sleep(10);
            } catch (InterruptedException e) { }
        }

        System.arraycopy(buffer, readIndex + 1, output, offset, length);
        readIndex += length;

        // check need reset indices
        _reset();

        return length;
    }

    /**
     * To be invoked from outside when incoming bytes arrive
     * @param value
     */
    public synchronized void doRead(byte[] value) {
        Logger.get().log("BleInputStream.doRead() length=" + value.length);

        // append to the buffer
        System.arraycopy(value, 0, buffer, lastIndex.get() + 1, value.length);
        lastIndex.addAndGet(value.length);
    }

    private boolean closed = false;

    @Override
    public void close() throws IOException {
        Logger.get().log("BleInputStream.close()");

        super.close();

        closed = true;
        _reset();
        buffer = null;
    }


}
