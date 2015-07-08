package com.googlecode.protobuf.blerpc.client;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import com.google.protobuf.BlockingRpcChannel;
import com.google.protobuf.RpcController;
import com.google.protobuf.ServiceException;
import com.googlecode.protobuf.blerpc.BleRpcConnectionFactory;
import com.googlecode.protobuf.blerpc.api.Api;
import com.googlecode.protobuf.socketrpc.RpcChannels;
import com.googlecode.protobuf.socketrpc.RpcConnectionFactory;
import com.googlecode.protobuf.socketrpc.SocketRpcController;

import java.text.MessageFormat;
import java.util.HashSet;
import java.util.Set;

public class MyActivity extends Activity implements BleRpcConnectionFactory.DiscoveryListener {

    private Button buttonDiscover;
    private Button buttonConnect;
    private Button buttonClear;
    private EditText logView;

    private Set<BluetoothDevice> foundDevices = new HashSet<BluetoothDevice>();

    @Override
    public void onFinished() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                logView.getText().append("\nDiscovery finished");
                foundDevices.clear();
            }
        });
    }

    @Override
    public void onStarted() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                logView.getText().append("\nDiscovery started");
                foundDevices.clear();
            }
        });
    }

    @Override
    public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // for some reason the same device can be found few times
                if (!foundDevices.contains(device)) {
                    String message = MessageFormat.format("\nBluetooth device found: {0} ({1})", device.getName(), device.getAddress());
                    logView.getText().append(message);
                    foundDevices.add(device);
                }
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        logView = (EditText) findViewById(R.id.log);
        buttonDiscover = (Button) findViewById(R.id.button_discover);
        buttonDiscover.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connectionFactory.discover(MyActivity.this);
            }
        });

        buttonConnect = (Button) findViewById(R.id.button_connect);
        buttonConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                connect();
            }
        });

        buttonClear = (Button) findViewById(R.id.button_clear);
        buttonClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                logView.getText().clear();
            }
        });

        connectionFactory = new BleRpcConnectionFactory(
                MyActivity.this,
                "6855f2ce-8dc6-4228-8bec-531167e00111",
                "09de1235-6594-4a2b-8d88-ad5eb8c00222",
                "c3a29c57-7a4b-492c-b7c4-7d807f000333",
                true
        );
    }

    private Api.WifiResponse response;
    private BleRpcConnectionFactory connectionFactory;

    private void connect() {
        logView.setText("");
        buttonConnect.setEnabled(false);

        new Thread(new Runnable() {
            @Override
            public void run() {
                BlockingRpcChannel channel = RpcChannels.newBlockingRpcChannel(connectionFactory);
                Api.WifiService.BlockingInterface service = Api.WifiService.newBlockingStub(channel);
                RpcController controller = new SocketRpcController();

                Api.WifiRequest request = Api.WifiRequest.newBuilder().build();
                try {
                    response = service.getWifiNetworks(controller, request);
                } catch (final ServiceException e) {

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            logView.getText().append("\nError: " + e.getMessage());
                            buttonConnect.setEnabled(true);
                        }
                    });
                    e.printStackTrace();
                    return;
                }

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        buttonConnect.setEnabled(true);

                        for (Api.WifiNetwork eachNetwork : response.getNetworksList()) {
                            logView.getText().append("-----------\n");
                            logView.getText().append(eachNetwork.toString());

                            System.out.println(eachNetwork); // list
                        }
                    }
                });
            }
        }).start();
    }
}
