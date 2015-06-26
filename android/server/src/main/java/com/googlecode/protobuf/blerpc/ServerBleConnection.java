package com.googlecode.protobuf.blerpc;

import com.google.protobuf.MessageLite;
import com.googlecode.protobuf.socketrpc.RpcConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Connection for BLE (peripheral role)
 */
public class ServerBleConnection implements RpcConnectionFactory.Connection {

    protected static Logger logger = LoggerFactory.getLogger(ServerBleConnection.class.getSimpleName());

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
        logger.debug(" ------ sendProtoMessage() started");

        // Write message
        if (delimited) {
            message.writeDelimitedTo(out);
            out.flush();
        } else {
            message.writeTo(out);
            out.flush();
        }

        logger.debug(" ------ sendProtoMessage() finished");
    }

    @Override
    public void receiveProtoMessage(MessageLite.Builder messageBuilder) throws IOException {
        logger.debug(" ------ receiveProtoMessage() started");

        // Read message
        if (delimited) {
            messageBuilder.mergeDelimitedFrom(in);
        } else {
            messageBuilder.mergeFrom(in);
        }

        logger.debug(" ------ receiveProtoMessage() finished");
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
