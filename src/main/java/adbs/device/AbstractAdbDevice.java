package adbs.device;

import adbs.channel.AdbChannel;
import adbs.channel.AdbChannelAddress;
import adbs.channel.AdbChannelInitializer;
import adbs.codec.*;
import adbs.connection.AdbAuthHandler;
import adbs.connection.AdbChannelProcessor;
import adbs.connection.AdbPacketCodec;
import adbs.constant.DeviceType;
import adbs.constant.Feature;
import adbs.constant.SyncID;
import adbs.entity.ConnectResult;
import adbs.entity.sync.*;
import adbs.exception.RemoteException;
import adbs.util.ChannelFactory;
import adbs.util.ChannelUtil;
import adbs.util.ShellUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProtocolException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;
import java.util.function.Predicate;

import static adbs.constant.Constants.DEFAULT_READ_TIMEOUT;
import static adbs.constant.Constants.SYNC_DATA_MAX;

public abstract class AbstractAdbDevice implements AdbDevice {

    private static final Logger logger = LoggerFactory.getLogger(AbstractAdbDevice.class);

    private static final AtomicReferenceFieldUpdater<AbstractAdbDevice, Promise> CONNECT_PROMISE_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(AbstractAdbDevice.class, Promise.class, "connectPromise");

    private final String serial;

    private final RSAPrivateCrtKey privateKey;

    private final byte[] publicKey;

    private final ChannelFactory factory;

    private final Map<CharSequence, AdbChannelInitializer> reverseMap;

    private final AtomicInteger channelIdGen;

    private final Queue<OpenTask> penddingQueue;

    //设备连接信息
    private volatile Channel connection;

    private volatile DeviceType type;

    private volatile String model;

    private volatile String product;

    private volatile String device;

    private volatile Set<Feature> features;

    private volatile Promise connectPromise;

    protected AbstractAdbDevice(String serial, RSAPrivateCrtKey privateKey, byte[] publicKey, ChannelFactory factory) {
        this.serial = serial;
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.factory = factory;
        this.reverseMap = new ConcurrentHashMap<>();
        this.channelIdGen = new AtomicInteger(1);
        this.penddingQueue = new LinkedList<>();
    }

    @Override
    public String serial() {
        return serial;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public String product() {
        return product;
    }

    @Override
    public String device() {
        return device;
    }

    @Override
    public Set<Feature> features() {
        return features;
    }

    @Override
    public DeviceType type() {
        return type;
    }

    private void ensureConnect() {
        if (this.connectPromise == null || this.connection == null) {
            throw new RuntimeException("not connect");
        }
    }

    @Override
    public <T> Attribute<T> attr(AttributeKey<T> key) {
        ensureConnect();
        return this.connection.attr(key);
    }

    @Override
    public <T> boolean hasAttr(AttributeKey<T> key) {
        ensureConnect();
        return this.connection.hasAttr(key);
    }

    public ChannelPipeline pipeline() {
        ensureConnect();
        return this.connection.pipeline();
    }

    public ByteBufAllocator alloc() {
        ensureConnect();
        return this.connection.alloc();
    }

    public EventLoop eventLoop() {
        ensureConnect();
        return this.connection.eventLoop();
    }

    public Future connect() {
        if (!CONNECT_PROMISE_UPDATER.compareAndSet(
                this, null, new DefaultPromise(GlobalEventExecutor.INSTANCE))) {
            return connectPromise;
        }
        ChannelFuture cf = factory.newChannel(this, new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ch.pipeline()
                        .addLast(new AdbPacketCodec())
                        .addLast(new AdbAuthHandler(privateKey, publicKey))
                        .addLast(new ChannelInboundHandlerAdapter(){
                            @Override
                            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                                if (evt instanceof ConnectResult) {
                                    ctx.pipeline().remove(this);
                                    ConnectResult result = (ConnectResult) evt;
                                    type = result.getType();
                                    model = result.getModel();
                                    product = result.getProduct();
                                    device = result.getDevice();
                                    features = result.getFeatures();
                                    ctx.pipeline().addLast(new AdbChannelProcessor(AbstractAdbDevice.this, channelIdGen, reverseMap));
                                    connectPromise.setSuccess(null);
                                }
                                ctx.fireUserEventTriggered(evt);
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                                ctx.pipeline().remove(this);
                                connectPromise.tryFailure(cause);
                                ctx.fireExceptionCaught(cause);
                            }

                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                ctx.pipeline().remove(this);
                                connectPromise.tryFailure(new ClosedChannelException());
                                ctx.fireChannelInactive();
                            }
                        });
            }
        });
        this.connection = cf.channel();
        cf.addListener(f -> {
            if (f.cause() != null) {
                connectPromise.setFailure(f.cause());
                connectPromise = null;
            }
        });

        connectPromise.addListener(f -> {
            if (f.cause() != null || f.isCancelled()) {
                OpenTask task;
                while ((task = penddingQueue.poll()) != null) {
                    task.promise.setFailure(new ClosedChannelException());
                }
                close();
            } else {
                OpenTask task;
                while ((task = penddingQueue.poll()) != null) {
                    task.run();
                }
            }
        });

        return connectPromise;
    }

    @Override
    public ChannelFuture open(String destination, long timeout, TimeUnit unit, AdbChannelInitializer initializer) {
        Long timeoutMs = unit.toMillis(timeout);
        ensureConnect();
        int localId = channelIdGen.getAndIncrement();
        String channelName = ChannelUtil.getChannelName(localId);
        AdbChannel channel = new AdbChannel(connection, localId, 0);
        channel.config().setConnectTimeoutMillis(timeoutMs.intValue());
        initializer.initChannel(channel);
        connection.pipeline().addLast(channelName, channel);
        try {
            if (connectPromise.isDone()) {
                return channel.connect(new AdbChannelAddress(destination, localId));
            } else {
                ChannelPromise promise = new DefaultChannelPromise(channel);
                if (!penddingQueue.add(new OpenTask(channel, promise, new AdbChannelAddress(destination, localId)))) {
                    throw new RejectedExecutionException();
                }
                return promise;
            }
        } catch (Throwable cause) {
            connection.pipeline().remove(channelName);
            throw new RuntimeException("open destination `" + destination + "` failed: " + cause.getMessage(), cause);
        }
    }

    private <R> Future<R> exec(String destination, long timeout, TimeUnit unit, Function<String, R> function, ChannelHandler... handlers) {
        Promise<R> promise = new DefaultPromise<>(eventLoop());
        StringBuilder sb = new StringBuilder();
        ChannelFuture cf = open(destination, timeout, unit, channel -> {
            channel.pipeline()
                    .addLast(handlers)
                    .addLast(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                            if (msg instanceof CharSequence) {
                                sb.append(msg);
                            } else {
                                ctx.fireChannelRead(msg);
                            }
                        }

                        @Override
                        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                            try {
                                R result = function.apply(sb.toString());
                                promise.setSuccess(result);
                            } catch (Throwable cause) {
                                promise.tryFailure(cause);
                            }
                        }
                    });
        });
        cf.addListener(f -> {
            if (f.cause() != null) {
                promise.setFailure(f.cause());
            }
        });
        if (timeout > 0) {
            eventLoop().schedule(() -> {
                TimeoutException cause = new TimeoutException("exec timed out: " + destination.trim());
                promise.tryFailure(cause);
            }, timeout, unit);
        }
        promise.addListener(f -> {
            cf.channel().close();
        });

        return promise;
    }

    @Override
    public <R> Future<R> exec(String destination, long timeout, TimeUnit unit, Function<String, R> function) {
        return exec(
                destination, timeout, unit, function,
                new StringDecoder(StandardCharsets.UTF_8),
                new StringEncoder(StandardCharsets.UTF_8));
    }

    @Override
    public Future<String> shell(String cmd, String... args) {
        StringBuilder sb = new StringBuilder();
        sb.append("shell:");
        if (cmd != null) {
            sb.append(ShellUtil.buildCmdLine(cmd, args));
        }
        sb.append("\0");
        return exec(sb.toString(), DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
    }

    @Override
    public ChannelFuture shell(boolean lineFramed, ChannelInboundHandler handler) {
        return open("shell:\0", DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS, channel -> {
            if (lineFramed) {
                channel.pipeline().addLast(new LineBasedFrameDecoder(8192));
            }
            channel.pipeline()
                    .addLast(new StringDecoder(StandardCharsets.UTF_8))
                    .addLast(new StringEncoder(StandardCharsets.UTF_8))
                    .addLast(handler);
        });
    }

    @Override
    public Future<SyncStat> stat(String path) {
        boolean hasStatV2 = features.contains(Feature.STAT_V2);
        SyncID sid = hasStatV2 ? SyncID.STAT_V2 : SyncID.LSTAT_V1;
        SyncPath syncPath = new SyncPath(sid, path);
        Promise<SyncStat> promise = new DefaultPromise<>(eventLoop());
        open(
                "sync:\0", DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS,
                channel -> {
                    channel.pipeline()
                            .addLast(new SyncStatDecoder())
                            .addLast(new SyncEncoder())
                            .addLast(new ChannelInboundHandlerAdapter(){

                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                    if (msg instanceof SyncFail) {
                                        promise.setFailure(new RemoteException(((SyncFail) msg).error));
                                    } else if (msg instanceof SyncStat) {
                                        promise.setSuccess((SyncStat) msg);
                                    } else {
                                        promise.setFailure(new ProtocolException("Error reply:" + msg));
                                    }
                                }
                            });
                })
                .addListener(f -> {
                    if (f.cause() != null) {
                        promise.setFailure(f.cause());
                    }
                })
                .channel()
                .writeAndFlush(syncPath)
                .addListener(f -> {
                    if (f.cause() != null) {
                        promise.setFailure(f.cause());
                    }
                });
        return promise;
    }

    @Override
    public Future<SyncDent[]> list(String path) {
        boolean hasLsV2 = features.contains(Feature.LS_V2);
        SyncID sid = hasLsV2 ? SyncID.LIST_V2 : SyncID.LIST_V1;
        SyncPath syncPath = new SyncPath(sid, path);
        Promise<SyncDent[]> promise = new DefaultPromise<>(eventLoop());
        open(
                "sync:\0", DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS,
                channel -> {
                    channel.pipeline()
                            .addLast(new SyncDentDecoder())
                            .addLast(new SyncDentAggregator())
                            .addLast(new SyncEncoder())
                            .addLast(new ChannelInboundHandlerAdapter(){

                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                    if (msg instanceof SyncFail) {
                                        promise.setFailure(new RemoteException(((SyncFail) msg).error));
                                    } else if (msg instanceof SyncDent[]) {
                                        promise.setSuccess((SyncDent[]) msg);
                                    } else {
                                        promise.setFailure(new ProtocolException("Error reply:" + msg));
                                    }
                                }
                            });
                })
                .addListener(f -> {
                    if (f.cause() != null) {
                        promise.setFailure(f.cause());
                    }
                })
                .channel()
                .writeAndFlush(syncPath)
                .addListener(f -> {
                    if (f.cause() != null) {
                        promise.setFailure(f.cause());
                    }
                });
        return promise;
    }

    @Override
    public Future pull(String src, OutputStream dest) {
        Promise promise = new DefaultPromise<>(eventLoop());
        open(
                "sync:\0", DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS,
                channel -> {
                    channel.pipeline()
                            .addLast(new SyncDataDecoder())
                            .addLast(new SyncEncoder())
                            .addLast(new ChannelInboundHandlerAdapter(){

                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                    if (msg instanceof SyncFail) {
                                        promise.setFailure(new RemoteException(((SyncFail) msg).error));
                                    } else if (msg instanceof SyncData) {
                                        ByteBuf buf = ((SyncData) msg).data;
                                        try {
                                            int size = buf.readableBytes();
                                            if (size > 0) {
                                                buf.readBytes(dest, size);
                                            }
                                        } catch (Throwable cause) {
                                            promise.setFailure(cause);
                                        } finally {
                                            ReferenceCountUtil.safeRelease(msg);
                                        }
                                    } else if (msg instanceof SyncDataDone) {
                                        promise.setSuccess(null);
                                        ctx.writeAndFlush(new SyncQuit());
                                    } else {
                                        promise.setFailure(new ProtocolException("Error reply:" + msg));
                                    }
                                }
                            });
                })
                .addListener(f -> {
                    if (f.cause() != null) {
                        promise.setFailure(f.cause());
                    }
                })
                .channel()
                .writeAndFlush(new SyncPath(SyncID.RECV_V1, src))
                .addListener(f -> {
                    if (f.cause() != null) {
                        promise.setFailure(f.cause());
                    }
                });
        return promise;
    }

    @Override
    public Future push(InputStream src, String dest, int mode, int mtime) throws IOException {
        String destAndMode = dest + "," + mode;
        Promise promise = new DefaultPromise<>(eventLoop());
        ChannelFuture cf = open(
                "sync:\0", DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS,
                channel -> {
                    channel.pipeline()
                            .addLast(new SyncDecoder())
                            .addLast(new SyncEncoder())
                            .addLast(new ChannelInboundHandlerAdapter(){

                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                    if (msg instanceof SyncFail) {
                                        promise.setFailure(new RemoteException(((SyncFail) msg).error));
                                    } else if (msg instanceof SyncOkay) {
                                        promise.setSuccess(null);
                                        ctx.writeAndFlush(new SyncQuit());
                                    } else {
                                        promise.setFailure(new ProtocolException("Error reply:" + msg));
                                    }
                                }
                            });
                })
                .addListener(f -> {
                    if (f.cause() != null) {
                        promise.setFailure(f.cause());
                    }
                });

        cf.channel()
                .writeAndFlush(new SyncPath(SyncID.SEND_V1, destAndMode))
                .addListener(f -> {
                    if (f.cause() != null) {
                        promise.setFailure(f.cause());
                    }
                });
        while (true) {
            byte[] data = new byte[SYNC_DATA_MAX];
            ByteBuf payload = null;
            try {
                int size = src.read(data);
                if (size == -1) {
                    break;
                }
                if (size == 0) {
                    continue;
                }
                payload = Unpooled.wrappedBuffer(data, 0, size);
                cf.channel()
                        .writeAndFlush(payload)
                        .addListener(f -> {
                            if (f.cause() != null) {
                                promise.setFailure(f.cause());
                            }
                        });
            } catch (Throwable cause) {
                if (payload != null) {
                    ReferenceCountUtil.safeRelease(payload);
                }
                if (cause instanceof IOException) {
                    throw (IOException) cause;
                } else {
                    throw new IOException(cause.getMessage(), cause);
                }
            }
        }
        cf.channel()
                .writeAndFlush(new SyncDataDone(mtime))
                .addListener(f -> {
                    if (f.cause() != null) {
                        promise.setFailure(f.cause());
                    }
                });

        return promise;
    }

    /**
     * 此方法会等待设备重启
     * @param destination
     * @param timeout
     * @param unit
     * @param predicate
     * @return
     */
    private Future exec(String destination, long timeout, TimeUnit unit, Predicate<String> predicate) {
        Promise promise = new DefaultPromise(eventLoop());
        ChannelInboundHandlerAdapter reconnectHandler = new ChannelInboundHandlerAdapter() {
            @Override
            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                ctx.fireChannelInactive();
                connect().addListener(f -> {
                    if (f.cause() != null) {
                        promise.setFailure(f.cause());
                    }
                });
            }
        };
        exec(
                destination, timeout, unit,
                result -> {
                    result = StringUtils.trim(result);
                    if (predicate.test(result)) {
                        connection.pipeline().remove(reconnectHandler);
                        promise.setSuccess(null);
                    }
                    return null;
                },
                new StringDecoder(StandardCharsets.UTF_8),
                new StringEncoder(StandardCharsets.UTF_8),
                reconnectHandler
        );
        return promise;
    }

    @Override
    public Future root() {
        return exec(
                "root:\0", DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS,
                (Predicate<String>) s -> "adbd is already running as root".equals(s)
        );
    }

    @Override
    public Future unroot() {
        return exec(
                "unroot:\0", DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS,
                (Predicate<String>) s -> "adbd not running as root".equals(s)
        );
    }

    @Override
    public Future remount() {
        return exec(
                "remount:\0", DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS,
                (Function<String, Object>) s -> {
                    s = StringUtils.trim(s);
                    if (!"remount succeeded".equals(s)) {
                        throw new RuntimeException(s);
                    }
                    return null;
                });
    }

    private static void assertResult(String result) {
        if (result.startsWith("FAIL")) {
            int len = Integer.valueOf(result.substring(4, 8), 16);
            throw new RuntimeException(result.substring(8, 8 + len));
        } else if (!"OKAY".equals(result)) {
            throw new RuntimeException("unknown reply: " + result);
        }
    }

    @Override
    public Future reverse(String destination, AdbChannelInitializer initializer) {
        String cmd = "reverse:forward:" + destination + ";" + destination + "\0";
        return exec(
                cmd, DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS,
                (Function<String, Object>) result -> {
                    assertResult(result);
                    return null;
                }
        );
    }

    @Override
    public Future<String[]> reverseList() {
        return exec(
                "reverse:list-forward\0", DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS,
                (Function<String, String[]>) result -> {
                    result = StringUtils.trim(result);
                    assertResult(result);
                    if (StringUtils.isEmpty(result)) {
                        return ArrayUtils.EMPTY_STRING_ARRAY;
                    }
                    return result.split("\r\n|\n|\r");
                }
        );
    }

    @Override
    public Future reverseRemove(String destination) {
        return exec(
                "reverse:killforward:" + destination + "\0",
                DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS,
                (Function<String, Object>) result -> {
                    assertResult(result);
                    reverseMap.remove(destination);
                    return null;
                }
        );
    }

    @Override
    public Future reverseRemoveAll() {
        return exec(
                "reverse:killforward-all\0", DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS,
                (Function<String, Object>) result -> {
                    assertResult(result);
                    reverseMap.clear();
                    return null;
                }
        );
    }

    protected abstract void doClose();

    @Override
    public ChannelFuture close() {
        this.connectPromise = null;
        return this.connection.close().addListener(f -> {
            doClose();
            if (f.cause() == null) {
                logger.info("connection `{}` closed", serial);
            } else {
                logger.error("connection `{}` close error:{}", serial, f.cause().getMessage(), f.cause());
            }
        });
    }

    private static class OpenTask implements Runnable {

        private final Channel channel;

        private final ChannelPromise promise;

        private final AdbChannelAddress address;

        public OpenTask(Channel channel, ChannelPromise promise, AdbChannelAddress address) {
            this.channel = channel;
            this.promise = promise;
            this.address = address;
        }

        @Override
        public void run() {
            channel.connect(address)
                    .addListener(f -> {
                        if (f.cause() != null) {
                            promise.setFailure(f.cause());
                        } else {
                            promise.setSuccess();
                        }
                    });
        }
    }
}
