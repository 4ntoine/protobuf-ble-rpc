package com.googlecode.protobuf.blerpc.api;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.widget.Toast;
import com.google.protobuf.RpcCallback;
import com.google.protobuf.RpcController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.MessageFormat;
import java.util.List;

/**
 *
 */
public class WifiServiceImpl extends Api.WifiService {

    private static Logger logger = LoggerFactory.getLogger(WifiServiceImpl.class.getSimpleName());

    private Context context;
    private WifiManager wifiManager;

    public WifiServiceImpl(Context context) {
        this.context = context;
        wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
    }

    private volatile boolean discovering = false;

    private BroadcastReceiver wifiNetworkReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // stop timer
            wifiHandler.removeCallbacks(wifiTimerRunnable);

            // prepare results
            List<ScanResult> scanResults = wifiManager.getScanResults();
            for (ScanResult eachResult : scanResults) {
                wifiResponseBuilder.addNetworks(Api.WifiNetwork.newBuilder()
                        .setSSID(eachResult.SSID)
                        .setBSSID(eachResult.BSSID)
                        .setCapabilities(eachResult.capabilities)
                        .setLevel(eachResult.level)
                        .setFrequency(eachResult.frequency)
                        .setTimestamp(eachResult.timestamp)
                        .build());
            }

            discovering = false;
        }
    };

    private void finishWifiDiscovery() {
        Api.WifiResponse wifiResponse = wifiResponseBuilder.build();
        wifiCallback.run(wifiResponse);

        wifiResponseBuilder = null;
        wifiCallback = null;
        context.unregisterReceiver(wifiNetworkReceiver);
    }

    private Api.WifiResponse.Builder wifiResponseBuilder;
    private RpcCallback<Api.WifiResponse> wifiCallback;

    private Runnable wifiTimerRunnable = new Runnable() {
        @Override
        public void run() {
            // send empty list
            discovering = false;
        }
    };

    private Handler wifiHandler = new Handler();

    private static final int WIFI_DISCOVERY_TIMEOUT = 30 * 1000;

    @Override
    public void getWifiNetworks(RpcController controller, Api.WifiRequest request, RpcCallback<Api.WifiResponse> done) {
        logger.debug("getWifiNetworks() started");

        // notify
        ((Activity) context).runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, "getWifiNetworks()", Toast.LENGTH_SHORT).show();
            }
        });

        discovering = true;

        // turn Wifi ON if
        if (!wifiManager.isWifiEnabled())
            wifiManager.setWifiEnabled(true);

        // prepare
        wifiResponseBuilder = Api.WifiResponse.newBuilder();
        wifiCallback = done;

        // start wifi discovery
        context.registerReceiver(wifiNetworkReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
        wifiManager.startScan();

        // schedule timer
        wifiHandler.removeCallbacks(wifiTimerRunnable);
        int timeout = (request.hasTimeout() ? request.getTimeout() : WIFI_DISCOVERY_TIMEOUT);
        wifiHandler.postDelayed(wifiTimerRunnable, timeout);

        // block until networks are found
        while (discovering) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
            }
        }

        finishWifiDiscovery();
        logger.debug("getWifiNetworks() finished");
    }

    @Override
    public void connectWifiNetwork(RpcController controller, Api.WifiConnectRequest request, RpcCallback<Api.WifiConnectResponse> done) {
        WifiConfiguration wifiConfig = new WifiConfiguration();
        wifiConfig.SSID = String.format("\"%s\"", request.getNetwork().getSSID());
        wifiConfig.preSharedKey = String.format("\"%s\"", request.getPassword());

        logger.debug(MessageFormat.format("Trying to connect to ssid={0} with password={1}",
                wifiConfig.SSID, request.getPassword()));

        // remember id
        int netId = wifiManager.addNetwork(wifiConfig);
        wifiManager.disconnect();
        wifiManager.enableNetwork(netId, true);
        boolean connected = wifiManager.reconnect();

        logger.debug(connected ? "Successfully connected" : "Failed to connect !");

        // response
        Api.WifiConnectResponse response = Api.WifiConnectResponse.newBuilder()
                .setConnected(connected)
                .build();

        // callback
        done.run(response);
    }
}
