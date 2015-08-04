package com.googlecode.protobuf.blerpc;

import android.app.Activity;
import android.bluetooth.*;
/*
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.os.ParcelUuid;
*/
import android.content.Context;
import android.widget.Toast;
import com.googlecode.protobuf.socketrpc.ServerRpcConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RpcConnectionFactory for BLE (peripheral role)
 */
public class ServerBleRpcConnectionFactory implements ServerRpcConnectionFactory {

    private Logger logger = LoggerFactory.getLogger(ServerBleRpcConnectionFactory.class.getSimpleName());

    private Map<BluetoothDevice, ServerBleConnection> connections = new HashMap<BluetoothDevice, ServerBleConnection>();

    private BluetoothAdapter adapter;
    private BluetoothManager manager;
    private BluetoothGattServer server;
    private UUID serviceUUID;
    private BluetoothGattService service;
    private BluetoothGattCharacteristic readCharacteristic;
    private BluetoothGattCharacteristic writeCharacteristic;
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

    private void _onNotificationSent(BluetoothDevice device, int status) {
        ServerBleConnection connection = connections.get(device);
        if (connection == null)
            return;

        if (status == BluetoothGatt.GATT_SUCCESS) {
            // as client reads we need notify output stream to set new value (remaining bytes)
            connection.getOut().notifyWritten();
        }
    }

    private String bleDeviceName;

    public ServerBleRpcConnectionFactory(
            Context context,
            String bleDeviceName,
            String serviceUUID,
            String readCharacteristicUUID,
            String writeCharacteristicUUID,
            boolean delimited) {

        this.bleDeviceName = bleDeviceName;
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

                    logger.debug("Client connected: " + device.toString());

                    // allow only 1 connection at the same time
                    stopAdvertising();
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

                    logger.debug("Client disconnected");
                    startAdvertising();
                }
            }

            @Override
            public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattDescriptor descriptor) {
                logger.debug("onDescriptorReadRequest()");

                // send current subscription state
                ServerBleConnection connection = connections.get(device);
                if (connection == null) {
                    logger.error("Connection not found");
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
                logger.debug("onDescriptorWriteRequest()");

                // subscribe/unsubscribe
                ServerBleConnection connection = connections.get(device);
                if (connection == null)
                    throw new IllegalStateException("Connection not found");

                // manage notification subscription
                if (Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                    Arrays.equals(value, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) {
                    connection.setSubscribed(true);

                    logger.debug("Client subscribed to changes");
                } else if (Arrays.equals(value, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                    connection.setSubscribed(false);

                    logger.debug("Client unsubscribed");
                }

                // send write confirmation
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }

            @Override
            public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic,
                                                     boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
                logger.debug("onCharacteristicWriteRequest() " + value.length + " bytes");

                // send write confirmation
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);

                // read value
                ServerBleConnection connection = connections.get(device);
                if (connection == null) {
                    showText("No connection found");
                    throw new IllegalStateException("Connection not found");
                }

                connection.getIn().doRead(value);

               logger.debug("Value written " + + value.length + " bytes");
            }

            @Override
            public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
               logger.debug("onCharacteristicReadRequest()");

                // send value
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, characteristic.getValue());

                ServerBleConnection connection = connections.get(device);
                if (connection == null)
                    throw new IllegalStateException("Connection not found");

                // as client reads we need notify output stream to set new value (remaining bytes)
                connection.getOut().notifyWritten();

               logger.debug("Value read " + characteristic.getValue());
            }

            /*
            @Override
            public void onNotificationSent(BluetoothDevice device, int status) {
                logger.debug("onNotificationSent: status = " + status);


                _onNotificationSent(device, status);
            }
            */
        });


        this.serviceUUID = UUID.fromString(serviceUUID);
        service = new BluetoothGattService(this.serviceUUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

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

        startAdvertising();
    }

    private boolean isAdvertising = false;

    private void stopAdvertising() {
        logger.debug("Stopping advertising ... ");
        adapter.setScanMode(BluetoothAdapter.SCAN_MODE_NONE, 0);

        isAdvertising = false;
    }

    public void shutDown() throws IOException {
        close();
        if (isAdvertising)
            stopAdvertising();
    }

    private void startAdvertising() {
        logger.debug("Starting advertising ...");

        adapter.setName(bleDeviceName);

        // advertised service UUID
        ByteBuffer advertisingUuidBytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        advertisingUuidBytes
                .putLong(serviceUUID.getLeastSignificantBits())
                .putLong(serviceUUID.getMostSignificantBits());

        server.setAdvDataEx(
                true,  // boolean advData
                true,  // boolean includeName
                true,  // boolean includeTxPower
                null,  // minInterval
                null,  // maxInterval
                null,  // appearance
                null,  // manufacturerData
                null,  // serviceData
                advertisingUuidBytes.array() // byte[] advertisingUuid
        );
        logger.debug("Advertising service UUID={0} with includeName={1} and includeTxPower", serviceUUID.toString(), bleDeviceName);
        adapter.setScanMode(BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE, 0);

        isAdvertising = true;


        /*
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

               logger.debug("Started advertising");
            }

            @Override
            public void onStartFailure(int errorCode) {
                super.onStartFailure(errorCode);

                startedSuccessfully.set(false);
                started.set(true);

               logger.debug("Failed to advertise");
            }
        });
        */
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

    private boolean tryNotifyChanged(final BluetoothDevice device, BluetoothGattCharacteristic c, boolean confirm) {
        for (int i=0; i<NOTIFY_ATTEMPTS; i++) {

            try {
                if (server.notifyCharacteristicChanged(device, c, confirm)) {
                    logger.debug("server.notifyCharacteristicChanged() ok");

                    // simulate onNotificationSent event which is not supported by Hi-P
                    // WARNING: IT MAKES INTERACTION NOT RELIABLE !
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                logger.debug("simulate onNotificationSent(), wait for 2 ms");
                                Thread.sleep(2);  // 2 ms (le't say 2 ms is enough for the client to get)
                            } catch (InterruptedException e) {
                            }

                            _onNotificationSent(device, BluetoothGatt.GATT_SUCCESS);
                        }
                    }) .start();

                    return true;
                }
            } catch (Throwable t) {
               logger.debug("Crashed to notify !!");
                t.printStackTrace();
            }

            try {
               logger.debug("failed to notifyCharacteristicChanged(), retrying");
                Thread.sleep(50);
            } catch (InterruptedException e) {
            }
        }

        return false;
    }

    public boolean notifyChanged(BluetoothGattCharacteristic characteristic) {
       logger.debug("notifyChanged()");

        boolean notified = true;

        for (Map.Entry<BluetoothDevice, ServerBleConnection> eachEntry : connections.entrySet())
            if (eachEntry.getValue().isSubscribed()) {

                if (tryNotifyChanged(eachEntry.getKey(), characteristic, false)) {
                   logger.debug("notified " + eachEntry.getKey());
                } else {
                   logger.debug("failed to notify!");
                    notified = false;
                }
            }

        return notified;
    }
}
