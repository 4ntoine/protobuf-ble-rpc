package com.googlecode.protobuf.blerpc.server;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.EditText;
import com.googlecode.protobuf.blerpc.ServerBleRpcConnectionFactory;
import com.googlecode.protobuf.blerpc.api.WifiServiceImpl;
import com.googlecode.protobuf.socketrpc.RpcServer;
import com.googlecode.protobuf.socketrpc.ServerRpcConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.impl.EditTextLoggerFactory;

import com.googlecode.protobuf.blerpc.UUIDHelper;

import java.io.IOException;
import java.util.concurrent.Executors;

public class MyActivity extends Activity {

    private Logger logger;

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
        logger = LoggerFactory.getLogger(MyActivity.class.getSimpleName());

        initBle();
    }

    private void initBle() {
        // enable
        if (!BluetoothAdapter.getDefaultAdapter().isEnabled()) {
            logger.debug("Enabling BLE adapter ...");
            BluetoothAdapter.getDefaultAdapter().enable();

            // run server in 3 seconds to let adapter be enabled
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    createBluetoothServer();
                }
            }, 3000);

        } else {
            // immediate run server
            createBluetoothServer();
        }
    }

    private Handler handler = new Handler();
    private ServerBleRpcConnectionFactory rpcConnectionFactory;

    private void createBluetoothServer() {
        logger.debug("Creating BLE server");

        // server
        rpcConnectionFactory = new ServerBleRpcConnectionFactory(
                this,
                "Allie",
                UUIDHelper.expandUUID("FFE2"),
                UUIDHelper.expandUUID("FFE3"),
                UUIDHelper.expandUUID("FFE4"),

                // DIS service information
                new ServerBleRpcConnectionFactory.Dis(
                    "manufacturerString",
                    "modelNumber",
                    "serialNumber",
                    "hardwareRevision",
                    "firmwareRevision",
                    "softwareRevision",
                    "systemID"
                ),
                true);

        server = new RpcServer(rpcConnectionFactory, Executors.newFixedThreadPool(1), true);
        WifiServiceImpl service = new WifiServiceImpl(this);
        server.registerService(service); // For non-blocking impl
        server.startServer();
    }

    private void destroyBluetoothServer() {
        try {
            rpcConnectionFactory.shutDown();
        } catch (IOException e) {
            e.printStackTrace();
        }
        server.shutDown();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        destroyBluetoothServer();
    }
}
