package com.googlecode.protobuf.blerpc.server;

import android.app.Activity;
import android.os.Bundle;
import android.widget.EditText;
import com.googlecode.protobuf.blerpc.ServerBleRpcConnectionFactory;
import com.googlecode.protobuf.blerpc.api.WifiServiceImpl;
import com.googlecode.protobuf.socketrpc.RpcServer;
import com.googlecode.protobuf.socketrpc.ServerRpcConnectionFactory;
import org.slf4j.impl.EditTextLoggerFactory;

import java.util.concurrent.Executors;

public class MyActivity extends Activity {

    private EditText logView;
    private RpcServer server;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        logView = (EditText) findViewById(R.id.log);

        // logging params
        EditTextLoggerFactory loggerFactory = EditTextLoggerFactory.get();
        loggerFactory.setShowSender(true);
        loggerFactory.setShowTime(true);
        loggerFactory.setEditText(logView);

        createBluetoothServer();
    }

    private void createBluetoothServer() {
        // server
        ServerRpcConnectionFactory rpcConnectionFactory = new ServerBleRpcConnectionFactory(
                this,
                "6855f2ce-8dc6-4228-8bec-531167e00111",
                "09de1235-6594-4a2b-8d88-ad5eb8c00222",
                "c3a29c57-7a4b-492c-b7c4-7d807f000333",
                true);

        server = new RpcServer(rpcConnectionFactory, Executors.newFixedThreadPool(1), true);
        WifiServiceImpl service = new WifiServiceImpl(this);
        server.registerService(service); // For non-blocking impl
        server.startServer();
    }

    private void destroyBluetoothServer() {
        server.shutDown();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        destroyBluetoothServer();
    }
}
