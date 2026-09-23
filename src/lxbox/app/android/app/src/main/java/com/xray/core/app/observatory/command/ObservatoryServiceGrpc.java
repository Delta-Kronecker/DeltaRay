package com.xray.core.app.observatory.command;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.68.1)",
    comments = "Source: app/observatory/command/command.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class ObservatoryServiceGrpc {

  private ObservatoryServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "xray.core.app.observatory.command.ObservatoryService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.xray.core.app.observatory.command.GetOutboundStatusRequest,
      com.xray.core.app.observatory.command.GetOutboundStatusResponse> getGetOutboundStatusMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetOutboundStatus",
      requestType = com.xray.core.app.observatory.command.GetOutboundStatusRequest.class,
      responseType = com.xray.core.app.observatory.command.GetOutboundStatusResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.xray.core.app.observatory.command.GetOutboundStatusRequest,
      com.xray.core.app.observatory.command.GetOutboundStatusResponse> getGetOutboundStatusMethod() {
    io.grpc.MethodDescriptor<com.xray.core.app.observatory.command.GetOutboundStatusRequest, com.xray.core.app.observatory.command.GetOutboundStatusResponse> getGetOutboundStatusMethod;
    if ((getGetOutboundStatusMethod = ObservatoryServiceGrpc.getGetOutboundStatusMethod) == null) {
      synchronized (ObservatoryServiceGrpc.class) {
        if ((getGetOutboundStatusMethod = ObservatoryServiceGrpc.getGetOutboundStatusMethod) == null) {
          ObservatoryServiceGrpc.getGetOutboundStatusMethod = getGetOutboundStatusMethod =
              io.grpc.MethodDescriptor.<com.xray.core.app.observatory.command.GetOutboundStatusRequest, com.xray.core.app.observatory.command.GetOutboundStatusResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetOutboundStatus"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xray.core.app.observatory.command.GetOutboundStatusRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xray.core.app.observatory.command.GetOutboundStatusResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ObservatoryServiceMethodDescriptorSupplier("GetOutboundStatus"))
              .build();
        }
      }
    }
    return getGetOutboundStatusMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ObservatoryServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ObservatoryServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ObservatoryServiceStub>() {
        @java.lang.Override
        public ObservatoryServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ObservatoryServiceStub(channel, callOptions);
        }
      };
    return ObservatoryServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ObservatoryServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ObservatoryServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ObservatoryServiceBlockingStub>() {
        @java.lang.Override
        public ObservatoryServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ObservatoryServiceBlockingStub(channel, callOptions);
        }
      };
    return ObservatoryServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ObservatoryServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ObservatoryServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ObservatoryServiceFutureStub>() {
        @java.lang.Override
        public ObservatoryServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ObservatoryServiceFutureStub(channel, callOptions);
        }
      };
    return ObservatoryServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     */
    default void getOutboundStatus(com.xray.core.app.observatory.command.GetOutboundStatusRequest request,
        io.grpc.stub.StreamObserver<com.xray.core.app.observatory.command.GetOutboundStatusResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetOutboundStatusMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ObservatoryService.
   */
  public static abstract class ObservatoryServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ObservatoryServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ObservatoryService.
   */
  public static final class ObservatoryServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ObservatoryServiceStub> {
    private ObservatoryServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ObservatoryServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ObservatoryServiceStub(channel, callOptions);
    }

    /**
     */
    public void getOutboundStatus(com.xray.core.app.observatory.command.GetOutboundStatusRequest request,
        io.grpc.stub.StreamObserver<com.xray.core.app.observatory.command.GetOutboundStatusResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetOutboundStatusMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ObservatoryService.
   */
  public static final class ObservatoryServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ObservatoryServiceBlockingStub> {
    private ObservatoryServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ObservatoryServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ObservatoryServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.xray.core.app.observatory.command.GetOutboundStatusResponse getOutboundStatus(com.xray.core.app.observatory.command.GetOutboundStatusRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetOutboundStatusMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ObservatoryService.
   */
  public static final class ObservatoryServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ObservatoryServiceFutureStub> {
    private ObservatoryServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ObservatoryServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ObservatoryServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.xray.core.app.observatory.command.GetOutboundStatusResponse> getOutboundStatus(
        com.xray.core.app.observatory.command.GetOutboundStatusRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetOutboundStatusMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_GET_OUTBOUND_STATUS = 0;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_GET_OUTBOUND_STATUS:
          serviceImpl.getOutboundStatus((com.xray.core.app.observatory.command.GetOutboundStatusRequest) request,
              (io.grpc.stub.StreamObserver<com.xray.core.app.observatory.command.GetOutboundStatusResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getGetOutboundStatusMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.xray.core.app.observatory.command.GetOutboundStatusRequest,
              com.xray.core.app.observatory.command.GetOutboundStatusResponse>(
                service, METHODID_GET_OUTBOUND_STATUS)))
        .build();
  }

  private static abstract class ObservatoryServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ObservatoryServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.xray.core.app.observatory.command.Command.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ObservatoryService");
    }
  }

  private static final class ObservatoryServiceFileDescriptorSupplier
      extends ObservatoryServiceBaseDescriptorSupplier {
    ObservatoryServiceFileDescriptorSupplier() {}
  }

  private static final class ObservatoryServiceMethodDescriptorSupplier
      extends ObservatoryServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ObservatoryServiceMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (ObservatoryServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ObservatoryServiceFileDescriptorSupplier())
              .addMethod(getGetOutboundStatusMethod())
              .build();
        }
      }
    }
    return result;
  }
}
