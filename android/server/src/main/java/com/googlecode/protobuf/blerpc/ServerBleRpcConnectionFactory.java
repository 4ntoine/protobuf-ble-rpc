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
import android.os.Handler;
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
    private BluetoothGattService disService;
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

    public String getBleDeviceName() {
        return bleDeviceName;
    }

    public void setBleDeviceName(String bleDeviceName) {
        this.bleDeviceName = bleDeviceName;
    }

    /**
     * Data for DIS
     */
    public static class Dis {
        private String manufacturerName;
        private String modelNumber;
        private String serialNumber;
        private String hardwareRevision;
        private String firmwareRevision;
        private String softwareRevision;

        public Dis(
                 String manufacturerName,
                 String modelNumber,
                 String serialNumber,
                 String hardwareRevision,
                 String firmwareRevision,
                 String softwareRevision)
        {
            this.manufacturerName = manufacturerName;
            this.modelNumber = modelNumber;
            this.serialNumber = serialNumber;
            this.hardwareRevision = hardwareRevision;
            this.firmwareRevision = firmwareRevision;
            this.softwareRevision = softwareRevision;
        }
    }

    private Dis dis;

    private boolean disconnectUnsubscribedClients = false;

    public boolean isDisconnectUnsubscribedClients() {
        return disconnectUnsubscribedClients;
    }

    public void setDisconnectUnsubscribedClients(boolean disconnectUnsubscribedClients) {
        this.disconnectUnsubscribedClients = disconnectUnsubscribedClients;
    }

    public static final int DISCONNECT_UNSUBSCRIBED_INTERVAL = 3000;
    private int disconnectUnsubscribedInterval = DISCONNECT_UNSUBSCRIBED_INTERVAL;

    public int getDisconnectUnsubscribedInterval() {
        return disconnectUnsubscribedInterval;
    }

    public void setDisconnectUnsubscribedInterval(int disconnectUnsubscribedInterval) {
        this.disconnectUnsubscribedInterval = disconnectUnsubscribedInterval;
    }

    private Handler disconnectHandler = new Handler();
    private DisconnectRunnable disconnectRunnable = null;

    private class DisconnectRunnable extends Thread {

        private BluetoothDevice device;

        public DisconnectRunnable(BluetoothDevice device) {
            this.device = device;
        }

        @Override
        public void run() {
            logger.debug("Disconnect timeout runnable for {}", device);

            ServerBleConnection connection = connections.get(device);
            if (connection == null) {
                logger.debug("No connection found for {}, ignoring", device);
            } else {
                logger.warn("Force disconnect {}", device);
                server.cancelConnection(device);
            }
        }
    }

    protected void cleanup(BluetoothDevice device) {
        // device disconnected - connection closed
        ServerBleConnection connection = connections.get(device);
        try {
            connection.close();
        } catch (IOException e) {
            logger.error("failed to close connection to " + device, e);
        }
        connections.remove(connection);
        disconnectRunnable = null;
        logger.debug("Clean-up for disconnect {} done", device);

        startAdvertising();
    }

    public ServerBleRpcConnectionFactory(
            Context context,
            String bleDeviceName,
            String serviceUUID,
            String readCharacteristicUUID,
            String writeCharacteristicUUID,
            Dis dis,
            boolean delimited) {

        this.bleDeviceName = bleDeviceName;
        adapter = BluetoothAdapter.getDefaultAdapter();
        manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.delimited = delimited;
        this.context = context;
        this.dis = dis;

        server = manager.openGattServer(context, new BluetoothGattServerCallback() {
            @Override
            public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
                logger.debug("onConnectionStateChange(device={}, status={}, newState={})",
                        device.getName() + "(" + device.getAddress() + ")",
                        status,
                        newState);

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
                    logger.debug("Client disconnected");

                    cancelDisconnectRunnableIfHaving(device);
                    cleanup(device);
                }
            }

            @Override
            public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattDescriptor descriptor) {
                if (descriptor.getCharacteristic().getService() == disService) {
                    logger.debug("DIS onDescriptorReadRequest()");
                    server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, descriptor.getValue());
                    return;
                }

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
                if (descriptor.getCharacteristic().getService() == disService) {
                    logger.debug("DIS onDescriptorWriteRequest()");
                    server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, descriptor.getValue());
                    return;
                }

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

                    if (disconnectUnsubscribedClients) {
                        cancelDisconnectRunnableIfHaving(device); // the client can unsubscribe twice (iOS)

                        logger.debug("Schedule disconnecting {}", device);
                        disconnectRunnable = new DisconnectRunnable(device);
                        disconnectHandler.postDelayed(disconnectRunnable, disconnectUnsubscribedInterval);
                    }
                }

                // send write confirmation
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            }

            @Override
            public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic,
                                                     boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {

                if (characteristic.getService() == disService) {
                    logger.debug("DIS onCharacteristicWriteRequest()");
                    server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
                    return;
                }

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
                if (characteristic.getService() == disService) {
                    logger.debug("DIS onCharacteristicReadRequest()");
                    server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, characteristic.getValue());
                    return;
                }

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
        if (server == null) {
            Toast.makeText(context, "Failed to create GattServer", Toast.LENGTH_LONG).show();
            throw new RuntimeException("Failed to create");
        }

        // primary service
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

        // DIS
        if (dis != null) {
            logger.debug("Providing DIS");
            disService = new BluetoothGattService(
                    UUID.fromString(DIS_SERVICE_UUID),
                    BluetoothGattService.SERVICE_TYPE_PRIMARY);

            // manufacturer name
            BluetoothGattCharacteristic manufacturerNameChar = new BluetoothGattCharacteristic(
                    UUID.fromString(DIS_MANUFACTURER_UUID),
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ);
            manufacturerNameChar.setValue(dis.manufacturerName);
            disService.addCharacteristic(manufacturerNameChar);

            // model number
            BluetoothGattCharacteristic modelNumberChar = new BluetoothGattCharacteristic(
                    UUID.fromString(DIS_MODEL_NUMBER_UUID),
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ);
            modelNumberChar.setValue(dis.modelNumber);
            disService.addCharacteristic(modelNumberChar);

            // serial number
            BluetoothGattCharacteristic serialNumberChar = new BluetoothGattCharacteristic(
                    UUID.fromString(DIS_SERIAL_NUMBER_UUID),
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ);
            serialNumberChar.setValue(dis.serialNumber);
            disService.addCharacteristic(serialNumberChar);

            // hardware revision
            BluetoothGattCharacteristic hardwareRevisionChar = new BluetoothGattCharacteristic(
                    UUID.fromString(DIS_HARDWARE_REVISION_UUID),
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ);
            hardwareRevisionChar.setValue(dis.hardwareRevision);
            disService.addCharacteristic(hardwareRevisionChar);

            // firmware revision
            BluetoothGattCharacteristic firmwareRevisionChar = new BluetoothGattCharacteristic(
                    UUID.fromString(DIS_FIRMWARE_REVISION_UUID),
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ);
            firmwareRevisionChar.setValue(dis.firmwareRevision);
            disService.addCharacteristic(firmwareRevisionChar);

            // software revision
            BluetoothGattCharacteristic softwareRevisionChar = new BluetoothGattCharacteristic(
                    UUID.fromString(DIS_SOFTWARE_REVISION_UUID),
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ);
            softwareRevisionChar.setValue(dis.softwareRevision);
            disService.addCharacteristic(softwareRevisionChar);

            // system ID
            /*
            BluetoothGattCharacteristic systemIDChar = new BluetoothGattCharacteristic(
                    UUID.fromString(DIS_SYSTEM_ID_UUID),
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ);
            systemIDChar.setValue(getSystemId(adapter.getAddress()));
            disService.addCharacteristic(systemIDChar);
            */

            int attempts = 0;
            while (true) {
                try {

                    // sometimes we have NPE while doing `server.addService(disService)
                    // so sleep between any attempt
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                    }
                    server.addService(disService);
                    logger.debug("added DIS service to server");
                    break;
                } catch (NullPointerException e) {
                    if (++attempts > 3)
                        throw new RuntimeException("Failed to add DIS service to server");

                    logger.error("Failed to add DIS service, retrying in 5 ms");
                }
            }
        } else {
            logger.debug("NOT providing DIS");
        }

        startAdvertising();
    }

    private void cancelDisconnectRunnableIfHaving(BluetoothDevice device) {
        if (disconnectRunnable != null) {
            logger.debug("Cancelling disconnect runnable");
            disconnectHandler.removeCallbacks(disconnectRunnable);
            disconnectRunnable = null;
        }
    }

    private byte[] getSystemId(String bleMacAddress) {
        byte systemId[] = new byte[8];

        // convert own address from string to bytes
        byte ownAddress[] = new byte[6];
        String ownAddressParts[] = bleMacAddress.split(":");
        for (int i=0; i<6; i++) {
            ownAddress[i] = Byte.parseByte(ownAddressParts[i], 16);  // they are in Hex
        }

        /*

        Hi-P code to generate systemId

      // use 6 bytes of device address for 8 bytes of system ID value
      systemId[0] = ownAddress[0];
      systemId[1] = ownAddress[1];
      systemId[2] = ownAddress[2];

      // set middle bytes to zero
      systemId[4] = 0x00;
      systemId[3] = 0x00;

      // shift three bytes up
      systemId[7] = ownAddress[5];
      systemId[6] = ownAddress[4];
      systemId[5] = ownAddress[3];
         */

        ByteBuffer byteBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);

        systemId[0] = 0; //ownAddress[0];
        systemId[1] = 0; //ownAddress[1];
        systemId[2] = 0; //ownAddress[2];

        systemId[4] = 0x00;
        systemId[3] = 0x00;

        systemId[7] = ownAddress[5];
        systemId[6] = ownAddress[4];
        systemId[5] = ownAddress[3];

        return systemId;
    }

    private static String DIS_SERVICE_UUID           = UUIDHelper.expandUUID("180A");
    private static String DIS_MANUFACTURER_UUID      = UUIDHelper.expandUUID("2A29");
    private static String DIS_MODEL_NUMBER_UUID      = UUIDHelper.expandUUID("2A24");
    private static String DIS_SERIAL_NUMBER_UUID     = UUIDHelper.expandUUID("2A25");
    private static String DIS_HARDWARE_REVISION_UUID = UUIDHelper.expandUUID("2A27");
    private static String DIS_FIRMWARE_REVISION_UUID = UUIDHelper.expandUUID("2A26");
    private static String DIS_SOFTWARE_REVISION_UUID = UUIDHelper.expandUUID("2A28");
    private static String DIS_SYSTEM_ID_UUID         = UUIDHelper.expandUUID("2A23");
//    private static String DIS_CERT_INFO_UUID         = UUIDHelper.expandUUID("2A2A");

    private boolean isAdvertising = false;

    private void stopAdvertising() {
        logger.debug("Stopping advertising ... ");
        logger.debug("setScanMode(BluetoothAdapter.SCAN_MODE_NONE, 0)");
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

        UUID[] uuids = new UUID[] {
            serviceUUID,
            UUID.fromString(DIS_SERVICE_UUID)
        };

        // advertised service UUID
        ByteBuffer advertisingUuidBytes = ByteBuffer.allocate(16 * uuids.length).order(ByteOrder.LITTLE_ENDIAN);

        for (int i=0; i<uuids.length; i++) {
            advertisingUuidBytes
                .putLong(uuids[i].getLeastSignificantBits())
                .putLong(uuids[i].getMostSignificantBits());
        }

        logger.debug("Advertising service UUID={} with includeName={} and includeTxPower", serviceUUID.toString(), bleDeviceName);
        logger.debug("setAdvDataEx(true, true, true, null, null, null, null, null, ...)");

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
        logger.debug("setScanMode(BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE, 0)");
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
        server.close();

        logger.debug("server.close()");
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
