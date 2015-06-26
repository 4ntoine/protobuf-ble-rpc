package com.googlecode.protobuf.blerpc.client;

import android.app.Activity;
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

public class MyActivity extends Activity {

    private Button buttonView;
    private EditText logView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        logView = (EditText) findViewById(R.id.log);
        buttonView = (Button) findViewById(R.id.button);
        buttonView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!busy)
                    requestOverBle();
            }
        });
    }

    private Api.WifiResponse response;

    private boolean busy;

    private void requestOverBle() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                busy = true;

                // BLE connected
                RpcConnectionFactory connectionFactory = new BleRpcConnectionFactory(
                        MyActivity.this,
                        "6855f2ce-8dc6-4228-8bec-531167e00111",
                        "09de1235-6594-4a2b-8d88-ad5eb8c00222",
                        "c3a29c57-7a4b-492c-b7c4-7d807f000333",
                        true
                );
                BlockingRpcChannel channel = RpcChannels.newBlockingRpcChannel(connectionFactory);
                Api.WifiService.BlockingInterface service = Api.WifiService.newBlockingStub(channel);
                RpcController controller = new SocketRpcController();

                Api.WifiRequest request = Api.WifiRequest.newBuilder().build();
                try {
                    response = service.getWifiNetworks(controller, request);
                } catch (ServiceException e) {
                    e.printStackTrace();
                    return;
                } finally {
                    busy = false;
                }

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
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
