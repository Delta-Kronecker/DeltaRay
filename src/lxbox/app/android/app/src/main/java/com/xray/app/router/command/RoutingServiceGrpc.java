package com.xray.app.router.command;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.68.1)",
    comments = "Source: app/router/command/command.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class RoutingServiceGrpc {

  private RoutingServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "xray.app.router.command.RoutingService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.xray.app.router.command.SubscribeRoutingStatsRequest,
      com.xray.app.router.command.RoutingContext> getSubscribeRoutingStatsMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SubscribeRoutingStats",
      requestType = com.xray.app.router.command.SubscribeRoutingStatsRequest.class,
      responseType = com.xray.app.router.command.RoutingContext.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<com.xray.app.router.command.SubscribeRoutingStatsRequest,
      com.xray.app.router.command.RoutingContext> getSubscribeRoutingStatsMethod() {
    io.grpc.MethodDescriptor<com.xray.app.router.command.SubscribeRoutingStatsRequest, com.xray.app.router.command.RoutingContext> getSubscribeRoutingStatsMethod;
    if ((getSubscribeRoutingStatsMethod = RoutingServiceGrpc.getSubscribeRoutingStatsMethod) == null) {
      synchronized (RoutingServiceGrpc.class) {
        if ((getSubscribeRoutingStatsMethod = RoutingServiceGrpc.getSubscribeRoutingStatsMethod) == null) {
          RoutingServiceGrpc.getSubscribeRoutingStatsMethod = getSubscribeRoutingStatsMethod =
              io.grpc.MethodDescriptor.<com.xray.app.router.command.SubscribeRoutingStatsRequest, com.xray.app.router.command.RoutingContext>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SubscribeRoutingStats"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xray.app.router.command.SubscribeRoutingStatsRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xray.app.router.command.RoutingContext.getDefaultInstance()))
              .setSchemaDescriptor(new RoutingServiceMethodDescriptorSupplier("SubscribeRoutingStats"))
              .build();
        }
      }
    }
    return getSubscribeRoutingStatsMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.xray.app.router.command.TestRouteRequest,
      com.xray.app.router.command.RoutingContext> getTestRouteMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "TestRoute",
      requestType = com.xray.app.router.command.TestRouteRequest.class,
      responseType = com.xray.app.router.command.RoutingContext.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.xray.app.router.command.TestRouteRequest,
      com.xray.app.router.command.RoutingContext> getTestRouteMethod() {
    io.grpc.MethodDescriptor<com.xray.app.router.command.TestRouteRequest, com.xray.app.router.command.RoutingContext> getTestRouteMethod;
    if ((getTestRouteMethod = RoutingServiceGrpc.getTestRouteMethod) == null) {
      synchronized (RoutingServiceGrpc.class) {
        if ((getTestRouteMethod = RoutingServiceGrpc.getTestRouteMethod) == null) {
          RoutingServiceGrpc.getTestRouteMethod = getTestRouteMethod =
              io.grpc.MethodDescriptor.<com.xray.app.router.command.TestRouteRequest, com.xray.app.router.command.RoutingContext>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "TestRoute"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xray.app.router.command.TestRouteRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xray.app.router.command.RoutingContext.getDefaultInstance()))
              .setSchemaDescriptor(new RoutingServiceMethodDescriptorSupplier("TestRoute"))
              .build();
        }
      }
    }
    return getTestRouteMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.xray.app.router.command.GetBalancerInfoRequest,
      com.xray.app.router.command.GetBalancerInfoResponse> getGetBalancerInfoMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetBalancerInfo",
      requestType = com.xray.app.router.command.GetBalancerInfoRequest.class,
      responseType = com.xray.app.router.command.GetBalancerInfoResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.xray.app.router.command.GetBalancerInfoRequest,
      com.xray.app.router.command.GetBalancerInfoResponse> getGetBalancerInfoMethod() {
    io.grpc.MethodDescriptor<com.xray.app.router.command.GetBalancerInfoRequest, com.xray.app.router.command.GetBalancerInfoResponse> getGetBalancerInfoMethod;
    if ((getGetBalancerInfoMethod = RoutingServiceGrpc.getGetBalancerInfoMethod) == null) {
      synchronized (RoutingServiceGrpc.class) {
        if ((getGetBalancerInfoMethod = RoutingServiceGrpc.getGetBalancerInfoMethod) == null) {
          RoutingServiceGrpc.getGetBalancerInfoMethod = getGetBalancerInfoMethod =
              io.grpc.MethodDescriptor.<com.xray.app.router.command.GetBalancerInfoRequest, com.xray.app.router.command.GetBalancerInfoResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetBalancerInfo"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xray.app.router.command.GetBalancerInfoRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xray.app.router.command.GetBalancerInfoResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RoutingServiceMethodDescriptorSupplier("GetBalancerInfo"))
              .build();
        }
      }
    }
    return getGetBalancerInfoMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.xray.app.router.command.OverrideBalancerTargetRequest,
      com.xray.app.router.command.OverrideBalancerTargetResponse> getOverrideBalancerTargetMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "OverrideBalancerTarget",
      requestType = com.xray.app.router.command.OverrideBalancerTargetRequest.class,
      responseType = com.xray.app.router.command.OverrideBalancerTargetResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.xray.app.router.command.OverrideBalancerTargetRequest,
      com.xray.app.router.command.OverrideBalancerTargetResponse> getOverrideBalancerTargetMethod() {
    io.grpc.MethodDescriptor<com.xray.app.router.command.OverrideBalancerTargetRequest, com.xray.app.router.command.OverrideBalancerTargetResponse> getOverrideBalancerTargetMethod;
    if ((getOverrideBalancerTargetMethod = RoutingServiceGrpc.getOverrideBalancerTargetMethod) == null) {
      synchronized (RoutingServiceGrpc.class) {
        if ((getOverrideBalancerTargetMethod = RoutingServiceGrpc.getOverrideBalancerTargetMethod) == null) {
          RoutingServiceGrpc.getOverrideBalancerTargetMethod = getOverrideBalancerTargetMethod =
              io.grpc.MethodDescriptor.<com.xray.app.router.command.OverrideBalancerTargetRequest, com.xray.app.router.command.OverrideBalancerTargetResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "OverrideBalancerTarget"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xray.app.router.command.OverrideBalancerTargetRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xray.app.router.command.OverrideBalancerTargetResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RoutingServiceMethodDescriptorSupplier("OverrideBalancerTarget"))
              .build();
        }
      }
    }
    return getOverrideBalancerTargetMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.xray.app.router.command.AddRuleRequest,
      com.xray.app.router.command.AddRuleResponse> getAddRuleMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "AddRule",
      requestType = com.xray.app.router.command.AddRuleRequest.class,
      responseType = com.xray.app.router.command.AddRuleResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.xray.app.router.command.AddRuleRequest,
      com.xray.app.router.command.AddRuleResponse> getAddRuleMethod() {
    io.grpc.MethodDescriptor<com.xray.app.router.command.AddRuleRequest, com.xray.app.router.command.AddRuleResponse> getAddRuleMethod;
    if ((getAddRuleMethod = RoutingServiceGrpc.getAddRuleMethod) == null) {
      synchronized (RoutingServiceGrpc.class) {
        if ((getAddRuleMethod = RoutingServiceGrpc.getAddRuleMethod) == null) {
          RoutingServiceGrpc.getAddRuleMethod = getAddRuleMethod =
              io.grpc.MethodDescriptor.<com.xray.app.router.command.AddRuleRequest, com.xray.app.router.command.AddRuleResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "AddRule"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xray.app.router.command.AddRuleRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xray.app.router.command.AddRuleResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RoutingServiceMethodDescriptorSupplier("AddRule"))
              .build();
        }
      }
    }
    return getAddRuleMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.xray.app.router.command.RemoveRuleRequest,
      com.xray.app.router.command.RemoveRuleResponse> getRemoveRuleMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RemoveRule",
      requestType = com.xray.app.router.command.RemoveRuleRequest.class,
      responseType = com.xray.app.router.command.RemoveRuleResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.xray.app.router.command.RemoveRuleRequest,
      com.xray.app.router.command.RemoveRuleResponse> getRemoveRuleMethod() {
    io.grpc.MethodDescriptor<com.xray.app.router.command.RemoveRuleRequest, com.xray.app.router.command.RemoveRuleResponse> getRemoveRuleMethod;
    if ((getRemoveRuleMethod = RoutingServiceGrpc.getRemoveRuleMethod) == null) {
      synchronized (RoutingServiceGrpc.class) {
        if ((getRemoveRuleMethod = RoutingServiceGrpc.getRemoveRuleMethod) == null) {
          RoutingServiceGrpc.getRemoveRuleMethod = getRemoveRuleMethod =
              io.grpc.MethodDescriptor.<com.xray.app.router.command.RemoveRuleRequest, com.xray.app.router.command.RemoveRuleResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RemoveRule"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xray.app.router.command.RemoveRuleRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xray.app.router.command.RemoveRuleResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RoutingServiceMethodDescriptorSupplier("RemoveRule"))
              .build();
        }
      }
    }
    return getRemoveRuleMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.xray.app.router.command.ListRuleRequest,
      com.xray.app.router.command.ListRuleResponse> getListRuleMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ListRule",
      requestType = com.xray.app.router.command.ListRuleRequest.class,
      responseType = com.xray.app.router.command.ListRuleResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.xray.app.router.command.ListRuleRequest,
      com.xray.app.router.command.ListRuleResponse> getListRuleMethod() {
    io.grpc.MethodDescriptor<com.xray.app.router.command.ListRuleRequest, com.xray.app.router.command.ListRuleResponse> getListRuleMethod;
    if ((getListRuleMethod = RoutingServiceGrpc.getListRuleMethod) == null) {
      synchronized (RoutingServiceGrpc.class) {
        if ((getListRuleMethod = RoutingServiceGrpc.getListRuleMethod) == null) {
          RoutingServiceGrpc.getListRuleMethod = getListRuleMethod =
              io.grpc.MethodDescriptor.<com.xray.app.router.command.ListRuleRequest, com.xray.app.router.command.ListRuleResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ListRule"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xray.app.router.command.ListRuleRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.xray.app.router.command.ListRuleResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RoutingServiceMethodDescriptorSupplier("ListRule"))
              .build();
        }
      }
    }
    return getListRuleMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static RoutingServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RoutingServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RoutingServiceStub>() {
        @java.lang.Override
        public RoutingServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RoutingServiceStub(channel, callOptions);
        }
      };
    return RoutingServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static RoutingServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RoutingServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RoutingServiceBlockingStub>() {
        @java.lang.Override
        public RoutingServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RoutingServiceBlockingStub(channel, callOptions);
        }
      };
    return RoutingServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static RoutingServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RoutingServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RoutingServiceFutureStub>() {
        @java.lang.Override
        public RoutingServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RoutingServiceFutureStub(channel, callOptions);
        }
      };
    return RoutingServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     */
    default void subscribeRoutingStats(com.xray.app.router.command.SubscribeRoutingStatsRequest request,
        io.grpc.stub.StreamObserver<com.xray.app.router.command.RoutingContext> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSubscribeRoutingStatsMethod(), responseObserver);
    }

    /**
     */
    default void testRoute(com.xray.app.router.command.TestRouteRequest request,
        io.grpc.stub.StreamObserver<com.xray.app.router.command.RoutingContext> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getTestRouteMethod(), responseObserver);
    }

    /**
     */
    default void getBalancerInfo(com.xray.app.router.command.GetBalancerInfoRequest request,
        io.grpc.stub.StreamObserver<com.xray.app.router.command.GetBalancerInfoResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetBalancerInfoMethod(), responseObserver);
    }

    /**
     */
    default void overrideBalancerTarget(com.xray.app.router.command.OverrideBalancerTargetRequest request,
        io.grpc.stub.StreamObserver<com.xray.app.router.command.OverrideBalancerTargetResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getOverrideBalancerTargetMethod(), responseObserver);
    }

    /**
     */
    default void addRule(com.xray.app.router.command.AddRuleRequest request,
        io.grpc.stub.StreamObserver<com.xray.app.router.command.AddRuleResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getAddRuleMethod(), responseObserver);
    }

    /**
     */
    default void removeRule(com.xray.app.router.command.RemoveRuleRequest request,
        io.grpc.stub.StreamObserver<com.xray.app.router.command.RemoveRuleResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRemoveRuleMethod(), responseObserver);
    }

    /**
     */
    default void listRule(com.xray.app.router.command.ListRuleRequest request,
        io.grpc.stub.StreamObserver<com.xray.app.router.command.ListRuleResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getListRuleMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service RoutingService.
   */
  public static abstract class RoutingServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return RoutingServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service RoutingService.
   */
  public static final class RoutingServiceStub
      extends io.grpc.stub.AbstractAsyncStub<RoutingServiceStub> {
    private RoutingServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RoutingServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RoutingServiceStub(channel, callOptions);
    }

    /**
     */
    public void subscribeRoutingStats(com.xray.app.router.command.SubscribeRoutingStatsRequest request,
        io.grpc.stub.StreamObserver<com.xray.app.router.command.RoutingContext> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getSubscribeRoutingStatsMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void testRoute(com.xray.app.router.command.TestRouteRequest request,
        io.grpc.stub.StreamObserver<com.xray.app.router.command.RoutingContext> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getTestRouteMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void getBalancerInfo(com.xray.app.router.command.GetBalancerInfoRequest request,
        io.grpc.stub.StreamObserver<com.xray.app.router.command.GetBalancerInfoResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetBalancerInfoMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void overrideBalancerTarget(com.xray.app.router.command.OverrideBalancerTargetRequest request,
        io.grpc.stub.StreamObserver<com.xray.app.router.command.OverrideBalancerTargetResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getOverrideBalancerTargetMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void addRule(com.xray.app.router.command.AddRuleRequest request,
        io.grpc.stub.StreamObserver<com.xray.app.router.command.AddRuleResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getAddRuleMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void removeRule(com.xray.app.router.command.RemoveRuleRequest request,
        io.grpc.stub.StreamObserver<com.xray.app.router.command.RemoveRuleResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRemoveRuleMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void listRule(com.xray.app.router.command.ListRuleRequest request,
        io.grpc.stub.StreamObserver<com.xray.app.router.command.ListRuleResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getListRuleMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service RoutingService.
   */
  public static final class RoutingServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<RoutingServiceBlockingStub> {
    private RoutingServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RoutingServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RoutingServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public java.util.Iterator<com.xray.app.router.command.RoutingContext> subscribeRoutingStats(
        com.xray.app.router.command.SubscribeRoutingStatsRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getSubscribeRoutingStatsMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.xray.app.router.command.RoutingContext testRoute(com.xray.app.router.command.TestRouteRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getTestRouteMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.xray.app.router.command.GetBalancerInfoResponse getBalancerInfo(com.xray.app.router.command.GetBalancerInfoRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetBalancerInfoMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.xray.app.router.command.OverrideBalancerTargetResponse overrideBalancerTarget(com.xray.app.router.command.OverrideBalancerTargetRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getOverrideBalancerTargetMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.xray.app.router.command.AddRuleResponse addRule(com.xray.app.router.command.AddRuleRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getAddRuleMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.xray.app.router.command.RemoveRuleResponse removeRule(com.xray.app.router.command.RemoveRuleRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRemoveRuleMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.xray.app.router.command.ListRuleResponse listRule(com.xray.app.router.command.ListRuleRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getListRuleMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service RoutingService.
   */
  public static final class RoutingServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<RoutingServiceFutureStub> {
    private RoutingServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RoutingServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RoutingServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.xray.app.router.command.RoutingContext> testRoute(
        com.xray.app.router.command.TestRouteRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getTestRouteMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.xray.app.router.command.GetBalancerInfoResponse> getBalancerInfo(
        com.xray.app.router.command.GetBalancerInfoRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetBalancerInfoMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.xray.app.router.command.OverrideBalancerTargetResponse> overrideBalancerTarget(
        com.xray.app.router.command.OverrideBalancerTargetRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getOverrideBalancerTargetMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.xray.app.router.command.AddRuleResponse> addRule(
        com.xray.app.router.command.AddRuleRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getAddRuleMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.xray.app.router.command.RemoveRuleResponse> removeRule(
        com.xray.app.router.command.RemoveRuleRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRemoveRuleMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.xray.app.router.command.ListRuleResponse> listRule(
        com.xray.app.router.command.ListRuleRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getListRuleMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_SUBSCRIBE_ROUTING_STATS = 0;
  private static final int METHODID_TEST_ROUTE = 1;
  private static final int METHODID_GET_BALANCER_INFO = 2;
  private static final int METHODID_OVERRIDE_BALANCER_TARGET = 3;
  private static final int METHODID_ADD_RULE = 4;
  private static final int METHODID_REMOVE_RULE = 5;
  private static final int METHODID_LIST_RULE = 6;

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
        case METHODID_SUBSCRIBE_ROUTING_STATS:
          serviceImpl.subscribeRoutingStats((com.xray.app.router.command.SubscribeRoutingStatsRequest) request,
              (io.grpc.stub.StreamObserver<com.xray.app.router.command.RoutingContext>) responseObserver);
          break;
        case METHODID_TEST_ROUTE:
          serviceImpl.testRoute((com.xray.app.router.command.TestRouteRequest) request,
              (io.grpc.stub.StreamObserver<com.xray.app.router.command.RoutingContext>) responseObserver);
          break;
        case METHODID_GET_BALANCER_INFO:
          serviceImpl.getBalancerInfo((com.xray.app.router.command.GetBalancerInfoRequest) request,
              (io.grpc.stub.StreamObserver<com.xray.app.router.command.GetBalancerInfoResponse>) responseObserver);
          break;
        case METHODID_OVERRIDE_BALANCER_TARGET:
          serviceImpl.overrideBalancerTarget((com.xray.app.router.command.OverrideBalancerTargetRequest) request,
              (io.grpc.stub.StreamObserver<com.xray.app.router.command.OverrideBalancerTargetResponse>) responseObserver);
          break;
        case METHODID_ADD_RULE:
          serviceImpl.addRule((com.xray.app.router.command.AddRuleRequest) request,
              (io.grpc.stub.StreamObserver<com.xray.app.router.command.AddRuleResponse>) responseObserver);
          break;
        case METHODID_REMOVE_RULE:
          serviceImpl.removeRule((com.xray.app.router.command.RemoveRuleRequest) request,
              (io.grpc.stub.StreamObserver<com.xray.app.router.command.RemoveRuleResponse>) responseObserver);
          break;
        case METHODID_LIST_RULE:
          serviceImpl.listRule((com.xray.app.router.command.ListRuleRequest) request,
              (io.grpc.stub.StreamObserver<com.xray.app.router.command.ListRuleResponse>) responseObserver);
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
          getSubscribeRoutingStatsMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              com.xray.app.router.command.SubscribeRoutingStatsRequest,
              com.xray.app.router.command.RoutingContext>(
                service, METHODID_SUBSCRIBE_ROUTING_STATS)))
        .addMethod(
          getTestRouteMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.xray.app.router.command.TestRouteRequest,
              com.xray.app.router.command.RoutingContext>(
                service, METHODID_TEST_ROUTE)))
        .addMethod(
          getGetBalancerInfoMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.xray.app.router.command.GetBalancerInfoRequest,
              com.xray.app.router.command.GetBalancerInfoResponse>(
                service, METHODID_GET_BALANCER_INFO)))
        .addMethod(
          getOverrideBalancerTargetMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.xray.app.router.command.OverrideBalancerTargetRequest,
              com.xray.app.router.command.OverrideBalancerTargetResponse>(
                service, METHODID_OVERRIDE_BALANCER_TARGET)))
        .addMethod(
          getAddRuleMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.xray.app.router.command.AddRuleRequest,
              com.xray.app.router.command.AddRuleResponse>(
                service, METHODID_ADD_RULE)))
        .addMethod(
          getRemoveRuleMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.xray.app.router.command.RemoveRuleRequest,
              com.xray.app.router.command.RemoveRuleResponse>(
                service, METHODID_REMOVE_RULE)))
        .addMethod(
          getListRuleMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.xray.app.router.command.ListRuleRequest,
              com.xray.app.router.command.ListRuleResponse>(
                service, METHODID_LIST_RULE)))
        .build();
  }

  private static abstract class RoutingServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    RoutingServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.xray.app.router.command.Command.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("RoutingService");
    }
  }

  private static final class RoutingServiceFileDescriptorSupplier
      extends RoutingServiceBaseDescriptorSupplier {
    RoutingServiceFileDescriptorSupplier() {}
  }

  private static final class RoutingServiceMethodDescriptorSupplier
      extends RoutingServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    RoutingServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (RoutingServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new RoutingServiceFileDescriptorSupplier())
              .addMethod(getSubscribeRoutingStatsMethod())
              .addMethod(getTestRouteMethod())
              .addMethod(getGetBalancerInfoMethod())
              .addMethod(getOverrideBalancerTargetMethod())
              .addMethod(getAddRuleMethod())
              .addMethod(getRemoveRuleMethod())
              .addMethod(getListRuleMethod())
              .build();
        }
      }
    }
    return result;
  }
}
