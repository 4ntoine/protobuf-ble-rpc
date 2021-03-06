# Protobuf BLE RPC

## Description

Google's protocol buffer library makes writing rpc services easy, but it does not contain a rpc implementation. The transport details are left up to the user to implement.

This is a simple BLE based rpc implementation in java for people who want a simple implementation of their protobuf rpc services.

See:
* http://code.google.com/p/protobuf/
* http://code.google.com/apis/protocolbuffers/docs/overview.html

## Usage

api.proto:
```
message YourRequest {
	optional int32 timeout = 1;
}

message YourResponse {
	required string result = 1;
}

service YourService {
	rpc yourMethod (YourRequest) returns (YourResponse);
}
```

### Server-side (BLE peripheral)

```
ServerRpcConnectionFactory rpcConnectionFactory = new ServerBleRpcConnectionFactory(
	this, // android context
    "6855f2ce-8dc6-4228-8bec-531167e00111", // service UUID
    "09de1235-6594-4a2b-8d88-ad5eb8c00222", // write characteristic UUID
    "c3a29c57-7a4b-492c-b7c4-7d807f000333", // read characteristic UUID
    true);

RtspServer server = new RpcServer(rpcConnectionFactory, Executors.newFixedThreadPool(1), true);
YourServiceImpl service = new YourServiceImpl(this); // your service impl
server.registerService(service); // For non-blocking impl
server.startServer();

// ...

server.shutDown();
```

### Client-side (BLE central)

```
RpcConnectionFactory connectionFactory = new BleRpcConnectionFactory(
        MyActivity.this, // android context
        "6855f2ce-8dc6-4228-8bec-531167e00111", // service UUID
        "09de1235-6594-4a2b-8d88-ad5eb8c00222", // write characteristic UUID
        "c3a29c57-7a4b-492c-b7c4-7d807f000333", // read characteristic UUID
        true
);
BlockingRpcChannel channel = RpcChannels.newBlockingRpcChannel(connectionFactory);
YourService.BlockingInterface service = YourService.newBlockingStub(channel); // your service stub
RpcController controller = new SocketRpcController();

YourRequest request = YourRequest.newBuilder().build(); // your method argument
YourResponse response = service.yourMethod(controller, request); // your method invocation and response

// show response in UI
```
## How to compile

Built is made using Maven.

### Requirements
1. Protobuf jar: make sure you have `com.google.protobuf:protobuf-java:2.6.1` for protobuf 2.6.1 installed in local maven repo.

2. https://github.com/4ntoine/protobuf-socket-rpc. Install it in local maven repo (using `mvn install`). 

3. Android jar: make sure you have `com.google.android:android:5.0` installed in local maven repo. Use maven install plugin to install android.jar from Android SDK as maven artifact.

Now compile protobuf-ble-rpc (run in 'android' folder):
> mvn clean install

## Examples

Examples can be found in 'android-examples' folder.
So you will be able to get wifi networks list available on server android device.

See '/proto/api.proto' file and generated 'com.googlecode.protobuf.blerpc.api' package.

Make sure 'android' module compiled before compiling 'android-examples'.
Compile examples with maven (run in 'android-examples' folder):
> mvn clean install

Run 'server' on server device (Android 21 required) and `client` on client device (Android 18 required).
