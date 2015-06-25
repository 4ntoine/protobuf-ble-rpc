package com.googlecode.protobuf.blerpc;

import com.google.protobuf.MessageLite;
import com.googlecode.protobuf.socketrpc.RpcConnectionFactory;

import java.io.IOException;

/**
 * Connection for BLE (peripheral role)
 */
public class ServerBleConnection implements RpcConnectionFactory.Connection {

    private BleInputStream in;
    private ServerBleOutputStream out;
    private boolean delimited;
    private boolean subscribed; // device is subscribed to 'read character' notifications

    public boolean isSubscribed() {
        return subscribed;
    }

    public void setSubscribed(boolean subscribed) {
        this.subscribed = subscribed;
    }

    public BleInputStream getIn() {
        return in;
    }

    public ServerBleOutputStream getOut() {
        return out;
    }

    public ServerBleConnection(BleInputStream in, ServerBleOutputStream out, boolean delimited) {
        this.in = in;
        this.out = out;
        this.delimited = delimited;
    }

    @Override
    public void sendProtoMessage(MessageLite message) throws IOException {
        Logger.get().log(" ------ sendProtoMessage() started");

        // Write message
        if (delimited) {
            message.writeDelimitedTo(out);
            out.flush();
        } else {
            message.writeTo(out);
            out.flush();
        }

        Logger.get().log(" ------ sendProtoMessage() finished");
    }

    @Override
    public void receiveProtoMessage(MessageLite.Builder messageBuilder) throws IOException {
        Logger.get().log(" ------ receiveProtoMessage() started");

        // Read message
        if (delimited) {
            messageBuilder.mergeDelimitedFrom(in);
        } else {
            messageBuilder.mergeFrom(in);
        }

        Logger.get().log(" ------ receiveProtoMessage() finished");
    }

    private boolean closed = false;

    @Override
    public void close() throws IOException {
        in.close();
        out.close();

        closed = true;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }


}
