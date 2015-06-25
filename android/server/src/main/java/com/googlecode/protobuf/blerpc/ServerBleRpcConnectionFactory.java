package com.googlecode.protobuf.blerpc;

import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.ParcelUuid;
import android.widget.Toast;
import com.googlecode.protobuf.socketrpc.ServerRpcConnectionFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RpcConnectionFactory for BLE (peripheral role)
 */
public class ServerBleRpcConnectionFactory implements ServerRpcConnectionFactory {

    private Map<BluetoothDevice, ServerBleConnection> connections =
            new HashMap<BluetoothDevice, ServerBleConnection>();

    private BluetoothAdapter adapter;
    private BluetoothManager manager;
    private BluetoothGattServer server;
    private BluetoothGattService service;
    private BluetoothGattCharacteristic readCharacteristic;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothLeAdvertiser advertiser;
    private boolean delimited;

    private AtomicBoolean newConnectionReceived = new AtomicBoolean(false);
    private ServerBleConnection newConnection;
    private Context context;

    private void showText(final String message) {
        ((Activity)context).runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    public ServerBleRpcConnectionFactory(
            final Context context,
            String serviceUUID,
            String readCharacteristicUUID,
            String writeCharacteristicUUID,
            boolean delimited) {

        adapter = BluetoothAdapter.getDefaultAdapter();
        manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.delimited = delimited;
        this.context = context;

        server = manager.openGattServer(context, new BluetoothGattServerCallback() {
            @Override
            public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {

                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    // new device connected - new connection
                    BleInputStream in = new BleInputStream(BleInputStream.INPUT_BUFFER_SIZE, BleInputStream.READ_TIMEOUT);
                    ServerBleOutputStream out = new ServerBleOutputStream(BleOutputStream.OUTPUT_BUFFER_SIZE, readCharacteristic, ServerBleRpcConnectionFactory.this);

                    newConnection = new ServerBleConnection(in, out, ServerBleRpcConnectionFactory.this.delimited);
                    connections.put(device, newConnection);
                    newConnectionReceived.set(true); // signal new connection

                    Logger.get().log(getClass().getSimpleName() + " Client connected: " + device.toString());
                }

                if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    // device disconnected - connection closed
                    ServerBleConnection connection = connections.get(device);
                    try {
                        connection.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    connections.remove(connection);

                    Logger.get().log(getClass().getSimpleName() + " Client disconnected");
                }
            }

            @Override
            public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattDescriptor descriptor) {
                Logger.get().log(getClass().getSimpleName() + " onDescriptorReadRequest()");

                // send current subscription state
                ServerBleConnection connection = connections.get(device);
                if (connection == null) {
                    Logger.get().log(getClass().getSimpleName() + " Connection not found");
                    throw new IllegalStateException("Connection not found");
                }

                byte[] value = connection.isSubscribed()
                        ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }

            @Override
            public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor,
                                                 boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
                Logger.get().log("onDescriptorWriteRequest() " + value + " bytes");

                // subscribe/unsubscribe
                ServerBleConnection connection = connections.get(device);
                if (connection == null)
                    throw new IllegalStateException("Connection not found");

                // manage notification subscription
                if (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                    Arrays.equals(value, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) {
                    connection.setSubscribed(true);

                    Logger.get().log(getClass().getSimpleName() + " Client subscribed to changes");
                } else if (Arrays.equals(value, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                    connection.setSubscribed(false);

                    Logger.get().log(getClass().getSimpleName() + " Client unsubscribed");
                }

                // send write confirmation
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }

            @Override
            public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic,
                                                     boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
                Logger.get().log("onCharacteristicWriteRequest() " + value.length + " bytes");

                // send write confirmation
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);

                // read value
                ServerBleConnection connection = connections.get(device);
                if (connection == null) {
                    showText("No connection found");
                    throw new IllegalStateException("Connection not found");
                }

                connection.getIn().doRead(value);

                Logger.get().log("Value written " + + value.length + " bytes");
            }

            @Override
            public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
                Logger.get().log("onCharacteristicReadRequest()");

                // send value
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, characteristic.getValue());

                ServerBleConnection connection = connections.get(device);
                if (connection == null)
                    throw new IllegalStateException("Connection not found");

                // as client reads we need notify output stream to set new value (remaining bytes)
                connection.getOut().notifyWritten();

                Logger.get().log("Value read " + characteristic.getValue());
            }

            @Override
            public void onNotificationSent(BluetoothDevice device, int status) {
                Logger.get().log("onNotificationSent: status = " + status);

                ServerBleConnection connection = connections.get(device);
                if (connection == null)
                    return;

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    // as client reads we need notify output stream to set new value (remaining bytes)
                    connection.getOut().notifyWritten();
                }
            }
        });

        service = new BluetoothGattService(UUID.fromString(serviceUUID), BluetoothGattService.SERVICE_TYPE_PRIMARY);

        // read characteristic
        readCharacteristic =
                new BluetoothGattCharacteristic(
                        UUID.fromString(readCharacteristicUUID),
                        BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY, // read + notify
                        BluetoothGattCharacteristic.PERMISSION_READ);
        readCharacteristic.setValue(new byte[] {}); // empty value

        BluetoothGattDescriptor readNotifyDescriptor =
                new BluetoothGattDescriptor(
                        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"), // "Client config" descriptor (0x2902)
                        BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE); // read + write "subscribed" status
        readCharacteristic.addDescriptor(readNotifyDescriptor);
        service.addCharacteristic(readCharacteristic);

        // write characteristic
        writeCharacteristic = new BluetoothGattCharacteristic(
                UUID.fromString(writeCharacteristicUUID),
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
        );
        service.addCharacteristic(writeCharacteristic);
        server.addService(service);

        // advertise server
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser != null) {
            startAdvertising(UUID.fromString(serviceUUID), advertiser);
        } else
            throw new RuntimeException("Advertising not supported");
    }

    private void startAdvertising(UUID serviceUUID, BluetoothLeAdvertiser advertiser) {
        Logger.get().log("Starting advertising ...");
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setConnectable(true)
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceUuid(new ParcelUuid(serviceUUID)) // to make server discoverable using service UUID
                .setIncludeTxPowerLevel(true)
                .build();

        final AtomicBoolean started = new AtomicBoolean(false);
        final AtomicBoolean startedSuccessfully = new AtomicBoolean(false);

        advertiser.startAdvertising(settings, data, new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                super.onStartSuccess(settingsInEffect);

                startedSuccessfully.set(true);
                started.set(true);

                Logger.get().log("Started advertising");
            }

            @Override
            public void onStartFailure(int errorCode) {
                super.onStartFailure(errorCode);

                startedSuccessfully.set(false);
                started.set(true);

                Logger.get().log("Failed to advertise");
            }
        });

        /*while (!started.get())
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
            }

        if (!startedSuccessfully.get())
            throw new RuntimeException("Failed to advertise server");*/
    }

    @Override
    public void close() throws IOException {
        for (Map.Entry<BluetoothDevice, ServerBleConnection> eachEntry : connections.entrySet()) {
            eachEntry.getValue().close();
        }
        connections.clear();
    }

    @Override
    public Connection createConnection() throws IOException {
        while (!newConnectionReceived.get()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        ServerBleConnection _newConnection = this.newConnection;

        // reset
        newConnectionReceived.set(false);
        this.newConnection = null;

        return _newConnection;
    }

    private static final int NOTIFY_ATTEMPTS = 3;

    private boolean tryNotifyChanged(BluetoothDevice device, BluetoothGattCharacteristic c, boolean indication) {
        for (int i=0; i<NOTIFY_ATTEMPTS; i++) {
            if (server.notifyCharacteristicChanged(device, c, indication)) {
                Logger.get().log("notifyCharacteristicChanged()");
                return true;
            }

            try {
                Logger.get().log("failed to notifyCharacteristicChanged(), retrying");
                Thread.sleep(50);
            } catch (InterruptedException e) {
            }
        }

        return false;
    }

    public boolean notifyChanged(BluetoothGattCharacteristic characteristic) {
        Logger.get().log(getClass().getSimpleName() + ".notifyChanged()");

        boolean notified = true;

        for (Map.Entry<BluetoothDevice, ServerBleConnection> eachEntry : connections.entrySet())
            if (eachEntry.getValue().isSubscribed()) {

                if (tryNotifyChanged(eachEntry.getKey(), characteristic, false)) {
                    Logger.get().log(getClass().getSimpleName() + ": notified " + eachEntry.getKey());
                } else {
                    Logger.get().log(getClass().getSimpleName() + " Failed to notify!");
                    notified = false;
                }
            }

        return notified;
    }
}
