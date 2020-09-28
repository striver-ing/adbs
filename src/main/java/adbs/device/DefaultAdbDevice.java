package adbs.device;

import adbs.channel.AdbChannel;
import adbs.channel.AdbChannelAddress;
import adbs.channel.AdbChannelInitializer;
import adbs.channel.TCPReverse;
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
import io.netty.channel.*;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.DefaultAttributeMap;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Future;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;
import java.util.function.Predicate;

import static adbs.constant.Constants.DEFAULT_READ_TIMEOUT;
import static adbs.constant.Constants.SYNC_DATA_MAX;

public class DefaultAdbDevice extends DefaultAttributeMap implements AdbDevice {

    private static final Logger logger = LoggerFactory.getLogger(DefaultAdbDevice.class);

    private static final AtomicReferenceFieldUpdater<DefaultAdbDevice, Promise> CONNECT_PROMISE_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(DefaultAdbDevice.class, Promise.class, "connectPromise");

    private static final AtomicReferenceFieldUpdater<DefaultAdbDevice, Promise> CLOSE_PROMISE_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(DefaultAdbDevice.class, Promise.class, "closePromise");

    private final String serial;

    private final RSAPrivateCrtKey privateKey;

    private final byte[] publicKey;

    private final ChannelFactory factory;

    private final EventLoopGroup eventLoop;

    private final Map<CharSequence, AdbChannelInitializer> reverseMap;

    private final AtomicInteger channelIdGen;

    //设备连接信息
    private volatile Channel connection;

    private volatile DeviceType type;

    private volatile String model;

    private volatile String product;

    private volatile String device;

    private volatile Set<Feature> features;

    private volatile Promise connectPromise;

    private volatile Promise closePromise;

    public DefaultAdbDevice(String serial, RSAPrivateCrtKey privateKey, byte[] publicKey, ChannelFactory factory, EventLoopGroup eventLoop) {
        this.serial = serial;
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.factory = factory;
        this.eventLoop = eventLoop;
        this.reverseMap = new ConcurrentHashMap<>();
        this.channelIdGen = new AtomicInteger(1);
        this.connect();
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

    public ChannelPipeline pipeline() {
        return this.connection.pipeline();
    }

    public ByteBufAllocator alloc() {
        return this.connection.alloc();
    }

    public EventLoopGroup eventLoop() {
        return eventLoop;
    }

    public Future connectFuture() {
        return connectPromise;
    }

    public Future connect() {
        Promise promise = new DefaultPromise(eventLoop().next());
        if (!CONNECT_PROMISE_UPDATER.compareAndSet(
                this, null, promise)) {
            return connectPromise;
        }
        ChannelFuture cf = factory.newChannel(this, eventLoop(), ch -> {
            ch.pipeline()
                    .addLast("codec", new AdbPacketCodec())
                    .addLast("auth", new AdbAuthHandler(privateKey, publicKey))
                    .addLast("connect", new ChannelInboundHandlerAdapter(){
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
                                ctx.pipeline().addAfter("codec", "processor", new AdbChannelProcessor(DefaultAdbDevice.this, channelIdGen, reverseMap));
                                promise.trySuccess(null);
                            }
                            ctx.fireUserEventTriggered(evt);
                        }

                        @Override
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                            ctx.pipeline().remove(this);
                            promise.tryFailure(cause);
                            ctx.fireExceptionCaught(cause);
                        }

                        @Override
                        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                            ctx.pipeline().remove(this);
                            promise.tryFailure(new ClosedChannelException());
                            ctx.fireChannelInactive();
                        }
                    });
        });

        this.connection = cf.channel();

        cf.addListener(f -> {
            if (f.cause() != null) {
                promise.tryFailure(f.cause());
            }
        });

        int connectTimeoutMillis = connection.config().getConnectTimeoutMillis();
        if (connectTimeoutMillis > 0) {
            eventLoop().schedule(() -> {
                ConnectTimeoutException cause =
                        new ConnectTimeoutException("connection timed out: " + serial);
                promise.tryFailure(cause);
            }, connectTimeoutMillis, TimeUnit.MILLISECONDS);
        }

        promise.addListener(f -> {
            if (f.cause() != null || f.isCancelled()) {
                close0();
            }
        });

        return promise;
    }

    @Override
    public ChannelFuture open(String destination, long timeout, TimeUnit unit, AdbChannelInitializer initializer) {
        Long timeoutMs = unit.toMillis(timeout);
        int localId = channelIdGen.getAndIncrement();
        String channelName = ChannelUtil.getChannelName(localId);
        AdbChannel channel = new AdbChannel(connection, localId, 0);
        channel.config().setConnectTimeoutMillis(timeoutMs.intValue());
        ChannelPromise promise = new DefaultChannelPromise(channel);
        connectPromise.addListener(f0 -> {
            if (f0.cause() != null) {
                promise.setFailure(f0.cause());
            } else {
                try {
                    initializer.initChannel(channel);
                    connection.pipeline().addLast(channelName, channel);
                    channel.connect(new AdbChannelAddress(destination, localId))
                            .addListener(f1 -> {
                                if (f1.cause() != null) {
                                    promise.setFailure(f1.cause());
                                } else {
                                    promise.setSuccess();
                                }
                            });
                } catch (Throwable cause) {
                    promise.setFailure(cause);
                }
            }
        });
        return promise;
    }

    private <R> Future<R> exec(String destination, long timeout, TimeUnit unit, Function<String, R> function, ChannelHandler... handlers) {
        Promise<R> promise = new DefaultPromise<>(eventLoop().next());
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
        String shellCmd = ShellUtil.buildShellCmd(cmd, args);
        return exec(shellCmd, DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
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
    public ChannelFuture shell(String cmd, String[] args, boolean lineFramed, ChannelInboundHandler handler) {
        String shellCmd = ShellUtil.buildShellCmd(cmd, args);
        return open(shellCmd, DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS, channel -> {
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
        Promise<SyncStat> promise = new DefaultPromise<>(eventLoop().next());
        ChannelFuture cf = open(
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
        });
        cf.addListener(f0 -> {
            if (f0.cause() != null) {
                promise.setFailure(f0.cause());
            } else {
                boolean hasStatV2 = features.contains(Feature.STAT_V2);
                SyncID sid = hasStatV2 ? SyncID.STAT_V2 : SyncID.LSTAT_V1;
                SyncPath syncPath = new SyncPath(sid, path);
                cf.channel().writeAndFlush(syncPath)
                        .addListener(f1 -> {
                            if (f1.cause() != null) {
                                promise.setFailure(f1.cause());
                            }
                        });
            }
        });
        return promise;
    }

    @Override
    public Future<SyncDent[]> list(String path) {
        Promise<SyncDent[]> promise = new DefaultPromise<>(eventLoop().next());
        ChannelFuture cf = open(
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
        });
        cf.addListener(f0 -> {
            if (f0.cause() != null) {
                promise.setFailure(f0.cause());
            } else {
                boolean hasLsV2 = features.contains(Feature.LS_V2);
                SyncID sid = hasLsV2 ? SyncID.LIST_V2 : SyncID.LIST_V1;
                SyncPath syncPath = new SyncPath(sid, path);
                cf.channel().writeAndFlush(syncPath)
                        .addListener(f1 -> {
                            if (f1.cause() != null) {
                                promise.setFailure(f1.cause());
                            }
                        });
            }
        });
        return promise;
    }

    @Override
    public Future pull(String src, OutputStream dest) {
        Promise promise = new DefaultPromise<>(eventLoop().next());
        ChannelFuture cf = open(
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
        });
        cf.addListener(f0 -> {
            if (f0.cause() != null) {
                promise.setFailure(f0.cause());
            } else {
                cf.channel()
                        .writeAndFlush(new SyncPath(SyncID.RECV_V1, src))
                        .addListener(f -> {
                            if (f.cause() != null) {
                                promise.setFailure(f.cause());
                            }
                        });
            }
        });
        return promise;
    }

    @Override
    public Future push(InputStream src, String dest, int mode, int mtime) throws IOException {
        String destAndMode = dest + "," + mode;
        Promise promise = new DefaultPromise<>(eventLoop().next());
        ChannelFuture cf = open(
            "sync:\0", DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS,
            channel -> {
                channel.pipeline()
                        .addLast(new SyncDecoder())
                        .addLast(new SyncEncoder())
                        .addLast(new ChannelInboundHandlerAdapter(){

                            @Override
                            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                                //发送SEND指令
                                ctx.writeAndFlush(new SyncPath(SyncID.SEND_V1, destAndMode))
                                        .addListener(f1 -> {
                                            if (f1.cause() != null) {
                                                promise.setFailure(f1.cause());
                                            }
                                        });

                                //发送数据
                                //启动一个新的线程读取流并发送数据
                                new Thread() {
                                    @Override
                                    public void run() {
                                        try {
                                            while (true) {
                                                ByteBuf data = alloc().buffer(SYNC_DATA_MAX);
                                                boolean success = false;
                                                try {
                                                    int size = data.writeBytes(src, SYNC_DATA_MAX);
                                                    if (size == -1) {
                                                        break;
                                                    }
                                                    if (size == 0) {
                                                        continue;
                                                    }
                                                    ctx.writeAndFlush(new SyncData(data))
                                                            .addListener(f2 -> {
                                                                if (f2.cause() != null) {
                                                                    promise.setFailure(f2.cause());
                                                                }
                                                            });
                                                    success = true;
                                                } finally {
                                                    if (!success) {
                                                        ReferenceCountUtil.safeRelease(data);
                                                    }
                                                }
                                            }
                                            //发送done
                                            ctx.writeAndFlush(new SyncDataDone(mtime))
                                                    .addListener(f3 -> {
                                                        if (f3.cause() != null) {
                                                            promise.setFailure(f3.cause());
                                                        }
                                                    });
                                        } catch (Throwable cause) {
                                            promise.setFailure(cause);
                                        }
                                    }
                                }.start();
                            }

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
        });
        cf.addListener(f0 -> {
            if (f0.cause() != null) {
                promise.setFailure(f0.cause());
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
        Promise promise = new DefaultPromise(eventLoop().next());
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
                    reverseMap.put(destination, initializer);
                    return null;
                }
        );
    }

    @Override
    public Future reverse(String remote, String local) {
        String[] addr = local.split(":");
        String protocol;
        String host;
        int port;
        if (addr.length == 2) {
            protocol = addr[0];
            host = "127.0.0.1";
            port = Integer.valueOf(addr[1]);
        } else if (addr.length == 3) {
            protocol = addr[0];
            host = addr[1];
            port = Integer.valueOf(addr[2]);
        } else {
            throw new IllegalArgumentException("local");
        }
        if (!"tcp".equals(protocol)) {
            throw new IllegalArgumentException("local");
        }
        String cmd = "reverse:forward:" + remote + ";" + local + "\0";
        return exec(
                cmd, DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS,
                (Function<String, Object>) result -> {
                    assertResult(result);
                    reverseMap.put(local, new TCPReverse(host, port, connection.eventLoop()));
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

    protected void doClose() {

    }

    public Future close0() {
        Promise promise = new DefaultPromise(eventLoop().next());
        if (!CLOSE_PROMISE_UPDATER.compareAndSet(this, null, promise)) {
            return closePromise;
        }
        this.connectPromise = null;
        this.connection.close().addListener(f -> {
            if (f.cause() != null) {
                promise.tryFailure(f.cause());
                logger.error("[{}] connection close error:{}", serial, f.cause().getMessage(), f.cause());
            } else {
                promise.trySuccess(null);
                logger.info("[{}] connection closed", serial);
            }
            try {
                doClose();
            } finally {
                this.closePromise = null;
            }
        });
        return promise;
    }

    @Override
    public Future close() {
        return close0().addListener(f0 -> {
            eventLoop.shutdownGracefully().addListener(f1 -> {
                if (f1.cause() == null) {
                    logger.info("[{}] event loop terminated", serial);
                } else {
                    logger.error("[{}] event loop shutdown error:{}", serial, f1.cause().getMessage(), f1.cause());
                }
            });
        });
    }
}
