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
ServerRpcConnectionFactory rpcConnectionFactory = new ServerBleRpcConnectionFactory(this,
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
RpcConnectionFactory connectionFactory = new BleConnectionFactory(
        MyActivity.this, // context
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
