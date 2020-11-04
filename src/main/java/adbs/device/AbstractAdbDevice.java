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
import adbs.util.ChannelUtil;
import adbs.util.ShellUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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

import static adbs.constant.Constants.SYNC_DATA_MAX;

public abstract class AbstractAdbDevice extends DefaultAttributeMap implements AdbDevice {

    private static final Logger logger = LoggerFactory.getLogger(AbstractAdbDevice.class);

    private static final AtomicReferenceFieldUpdater<AbstractAdbDevice, Promise> CONNECT_PROMISE_UPDATER
            = AtomicReferenceFieldUpdater.newUpdater(AbstractAdbDevice.class, Promise.class, "connectPromise");

    private final String serial;

    private final RSAPrivateCrtKey privateKey;

    private final byte[] publicKey;

    private final Map<CharSequence, AdbChannelInitializer> reverseMap;

    private final AtomicInteger channelIdGen;

    private volatile Promise<Channel> connectPromise;

    private volatile Promise closePromise;

    private volatile DeviceType type;

    private volatile String model;

    private volatile String product;

    private volatile String device;

    private volatile Set<Feature> features;

    public AbstractAdbDevice(String serial, RSAPrivateCrtKey privateKey, byte[] publicKey) {
        this.serial = serial;
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.reverseMap = new ConcurrentHashMap<>();
        this.channelIdGen = new AtomicInteger(1);
    }

    @Override
    public boolean isClosed() {
        return closePromise != null && (closePromise.isDone() || closePromise.isCancelled());
    }

    @Override
    public String serial() {
        return serial;
    }

    @Override
    public DeviceType type() {
        return type;
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

    protected RSAPrivateCrtKey privateKey() {
        return privateKey;
    }

    protected byte[] publicKey() {
        return publicKey;
    }

    protected Future closeFuture() {
        return closePromise;
    }

    abstract protected int readTimeout();

    protected abstract ChannelFuture newChannel(AdbChannelInitializer initializer);

    protected void connect() {
        Promise<Channel> promise = new DefaultPromise<>(executor());
        if (!CONNECT_PROMISE_UPDATER.compareAndSet(this, null, promise)) {
            return;
        }
        this.closePromise = new DefaultPromise(executor());
        ChannelFuture cf = newChannel(ch -> {
            ch.pipeline()
                    .addLast(new ChannelInboundHandlerAdapter(){
                        @Override
                        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                            ctx.fireChannelInactive();
                            //连接断开的时候关闭连接
                            close();
                        }
                    })
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
                                ctx.pipeline().addAfter("codec", "processor", new AdbChannelProcessor(channelIdGen, reverseMap));
                                connectPromise.trySuccess(ctx.channel());
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
        });
        Channel channel = cf.channel();

        cf.addListener(f -> {
            if (f.cause() != null) {
                connectPromise.tryFailure(f.cause());
            }
        });

        int connectTimeoutMillis = channel.config().getConnectTimeoutMillis();
        if (connectTimeoutMillis > 0) {
            executor().schedule(() -> {
                ConnectTimeoutException cause =
                        new ConnectTimeoutException("connection timed out: " + serial);
                connectPromise.tryFailure(cause);
            }, connectTimeoutMillis, TimeUnit.MILLISECONDS);
        }

        connectPromise.addListener(f -> {
            if (f.cause() != null || f.isCancelled()) {
                close().addListener(f1 -> {
                    if (f1.cause() != null) {
                        logger.warn("close channel failed:{}", f1.cause().getMessage(), f1.cause());
                    }
                });
            }
        });
    }

    @Override
    public Future<Channel> open(String destination, AdbChannelInitializer initializer) {
        //如果连接被关闭的情况下，直接抛出异常
        if (isClosed()) {
            throw new RuntimeException("Connection Closed");
        }
        Promise<Channel> promise = new DefaultPromise<>(executor());
        Long timeoutMs = TimeUnit.SECONDS.toMillis(readTimeout());
        int localId = channelIdGen.getAndIncrement();
        String channelName = ChannelUtil.getChannelName(localId);
        connectPromise.addListener((Future<Channel> f0) -> {
            if (f0.cause() != null) {
                promise.tryFailure(f0.cause());
            } else {
                try {
                    Channel channel = f0.getNow();
                    AdbChannel adbChannel = new AdbChannel(channel, localId, 0);
                    adbChannel.config().setConnectTimeoutMillis(timeoutMs.intValue());
                    initializer.initChannel(adbChannel);
                    channel.pipeline().addLast(channelName, adbChannel);
                    adbChannel.connect(new AdbChannelAddress(destination, localId))
                            .addListener((ChannelFuture f1) -> {
                                if (f1.cause() != null) {
                                    promise.tryFailure(f1.cause());
                                } else {
                                    promise.trySuccess(f1.channel());
                                }
                            });
                } catch (Throwable cause) {
                    promise.tryFailure(cause);
                }
            }
        });
        return promise;
    }

    @Override
    public Future<String> exec(String destination) {
        Promise<String> promise = new DefaultPromise<>(executor());
        StringBuilder sb = new StringBuilder();
        Future<Channel> future = open(destination, channel -> {
            channel.pipeline()
                    .addLast(new StringDecoder(StandardCharsets.UTF_8))
                    .addLast(new StringEncoder(StandardCharsets.UTF_8))
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
                                promise.trySuccess(sb.toString());
                            } catch (Throwable cause) {
                                promise.tryFailure(cause);
                            }
                        }

                        @Override
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                            promise.tryFailure(cause);
                        }
                    });
        });
        future.addListener((Future<Channel> f) -> {
            if (f.cause() != null) {
                promise.tryFailure(f.cause());
            } else {
                Channel channel = f.getNow();
                promise.addListener(f1 -> {
                    channel.close();
                });
            }
        });
        int readTimeout = readTimeout();
        if (readTimeout > 0) {
            executor().schedule(() -> {
                TimeoutException cause = new TimeoutException("exec timed out: " + destination.trim());
                promise.tryFailure(cause);
            }, readTimeout, TimeUnit.SECONDS);
        }

        return promise;
    }

    @Override
    public Future<String> shell(String cmd, String... args) {
        String shellCmd = ShellUtil.buildShellCmd(cmd, args);
        return exec(shellCmd);
    }

    @Override
    public Future<Channel> shell(boolean lineFramed, ChannelInboundHandler handler) {
        return open("shell:\0", channel -> {
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
    public Future<Channel> shell(String cmd, String[] args, boolean lineFramed, ChannelInboundHandler handler) {
        String shellCmd = ShellUtil.buildShellCmd(cmd, args);
        return open(shellCmd, channel -> {
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
        Promise<SyncStat> promise = new DefaultPromise<>(executor());
        Future<Channel> future = open(
                "sync:\0",
                channel -> {
                    channel.pipeline()
                            .addLast(new SyncStatDecoder())
                            .addLast(new SyncEncoder())
                            .addLast(new ChannelInboundHandlerAdapter(){

                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                    if (msg instanceof SyncFail) {
                                        promise.tryFailure(new RemoteException(((SyncFail) msg).error));
                                    } else if (msg instanceof SyncStat) {
                                        promise.trySuccess((SyncStat) msg);
                                    } else {
                                        promise.tryFailure(new ProtocolException("Error reply:" + msg));
                                    }
                                }

                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                                    promise.tryFailure(cause);
                                }

                                @Override
                                public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                    promise.tryFailure(new ClosedChannelException());
                                }
                            });
                });
        future.addListener((Future<Channel> f0) -> {
            if (f0.cause() != null) {
                promise.tryFailure(f0.cause());
            } else {
                Channel channel = f0.getNow();
                promise.addListener(f -> {
                    channel.writeAndFlush(new SyncQuit());
                });
                boolean hasStatV2 = features.contains(Feature.STAT_V2);
                SyncID sid = hasStatV2 ? SyncID.STAT_V2 : SyncID.LSTAT_V1;
                SyncPath syncPath = new SyncPath(sid, path);
                channel.writeAndFlush(syncPath)
                        .addListener(f1 -> {
                            if (f1.cause() != null) {
                                promise.tryFailure(f1.cause());
                            }
                        });
            }
        });
        return promise;
    }

    @Override
    public Future<SyncDent[]> list(String path) {
        Promise<SyncDent[]> promise = new DefaultPromise<>(executor());
        Future<Channel> future = open(
                "sync:\0",
                channel -> {
                    channel.pipeline()
                            .addLast(new SyncDentDecoder())
                            .addLast(new SyncDentAggregator())
                            .addLast(new SyncEncoder())
                            .addLast(new ChannelInboundHandlerAdapter(){

                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                    if (msg instanceof SyncFail) {
                                        promise.tryFailure(new RemoteException(((SyncFail) msg).error));
                                    } else if (msg instanceof SyncDent[]) {
                                        promise.trySuccess((SyncDent[]) msg);
                                    } else {
                                        promise.tryFailure(new ProtocolException("Error reply:" + msg));
                                    }
                                }

                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                                    promise.tryFailure(cause);
                                }

                                @Override
                                public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                    promise.tryFailure(new ClosedChannelException());
                                }
                            });
                });
        future.addListener((Future<Channel> f0) -> {
            if (f0.cause() != null) {
                promise.tryFailure(f0.cause());
            } else {
                Channel channel = f0.getNow();
                promise.addListener(f -> {
                    channel.writeAndFlush(new SyncQuit());
                });
                boolean hasLsV2 = features.contains(Feature.LS_V2);
                SyncID sid = hasLsV2 ? SyncID.LIST_V2 : SyncID.LIST_V1;
                SyncPath syncPath = new SyncPath(sid, path);
                channel.writeAndFlush(syncPath)
                        .addListener(f1 -> {
                            if (f1.cause() != null) {
                                promise.tryFailure(f1.cause());
                            }
                        });
            }
        });
        return promise;
    }

    @Override
    public Future pull(String src, OutputStream dest) {
        Promise promise = new DefaultPromise<>(executor());
        Future<Channel> future = open(
                "sync:\0",
                channel -> {
                    channel.pipeline()
                            .addLast(new SyncDataDecoder())
                            .addLast(new SyncEncoder())
                            .addLast(new ChannelInboundHandlerAdapter(){

                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                    if (msg instanceof SyncFail) {
                                        promise.tryFailure(new RemoteException(((SyncFail) msg).error));
                                    } else if (msg instanceof SyncData) {
                                        ByteBuf buf = ((SyncData) msg).data;
                                        try {
                                            int size = buf.readableBytes();
                                            if (size > 0) {
                                                buf.readBytes(dest, size);
                                            }
                                        } catch (Throwable cause) {
                                            promise.tryFailure(cause);
                                        } finally {
                                            ReferenceCountUtil.safeRelease(msg);
                                        }
                                    } else if (msg instanceof SyncDataDone) {
                                        promise.trySuccess(null);
                                    } else {
                                        promise.tryFailure(new ProtocolException("Error reply:" + msg));
                                    }
                                }

                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                                    promise.tryFailure(cause);
                                }

                                @Override
                                public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                    promise.tryFailure(new ClosedChannelException());
                                }
                            });
                });
        future.addListener((Future<Channel> f0) -> {
            if (f0.cause() != null) {
                promise.tryFailure(f0.cause());
            } else {
                Channel channel = f0.getNow();
                promise.addListener(f -> {
                    channel.writeAndFlush(new SyncQuit());
                });
                channel.writeAndFlush(new SyncPath(SyncID.RECV_V1, src))
                        .addListener(f -> {
                            if (f.cause() != null) {
                                promise.tryFailure(f.cause());
                            }
                        });
            }
        });
        return promise;
    }

    @Override
    public Future push(InputStream src, String dest, int mode, int mtime) {
        String destAndMode = dest + "," + mode;
        Promise promise = new DefaultPromise<>(executor());
        Future<Channel> future = open(
                "sync:\0",
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
                                                    promise.tryFailure(f1.cause());
                                                }
                                            });
                                    //发送数据
                                    //启动一个新的线程读取流并发送数据
                                    new Thread() {
                                        @Override
                                        public void run() {
                                            try {
                                                while (true) {
                                                    byte[] buffer = new byte[SYNC_DATA_MAX];
                                                    int size = src.read(buffer);
                                                    if (size == -1) {
                                                        break;
                                                    }
                                                    if (size == 0) {
                                                        continue;
                                                    }
                                                    ByteBuf payload = Unpooled.wrappedBuffer(buffer, 0, size);
                                                    try {
                                                        ctx.writeAndFlush(new SyncData(payload))
                                                                .addListener(f2 -> {
                                                                    if (f2.cause() != null) {
                                                                        promise.tryFailure(f2.cause());
                                                                    }
                                                                });
                                                    } catch (Throwable cause) {
                                                        ReferenceCountUtil.safeRelease(payload);
                                                        throw cause;
                                                    }
                                                }
                                                //发送done
                                                ctx.writeAndFlush(new SyncDataDone(mtime))
                                                        .addListener(f3 -> {
                                                            if (f3.cause() != null) {
                                                                promise.tryFailure(f3.cause());
                                                            }
                                                        });
                                            } catch (Throwable cause) {
                                                promise.tryFailure(cause);
                                            }
                                        }
                                    }.start();
                                }

                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                    if (msg instanceof SyncFail) {
                                        promise.tryFailure(new RemoteException(((SyncFail) msg).error));
                                    } else if (msg instanceof SyncOkay) {
                                        promise.trySuccess(null);
                                    } else {
                                        promise.tryFailure(new ProtocolException("Error reply:" + msg));
                                    }
                                }

                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                                    promise.tryFailure(cause);
                                }

                                @Override
                                public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                    promise.tryFailure(new ClosedChannelException());
                                }
                            });
                });
        future.addListener((Future<Channel> f0) -> {
            if (f0.cause() != null) {
                promise.tryFailure(f0.cause());
            } else {
                Channel channel = f0.getNow();
                promise.addListener(f -> {
                    channel.writeAndFlush(new SyncQuit());
                });
            }
        });

        return promise;
    }


    @Override
    public Future root() {
        Promise promise = new DefaultPromise<>(executor());
        exec("root:\0").addListener((Future<String> f) -> {
                    if (f.cause() != null) {
                        promise.tryFailure(f.cause());
                    } else {
                        String s = StringUtils.trim(f.getNow());
                        if (s.equals("adbd is already running as root")) {
                            promise.trySuccess(null);
                        } else {
                            //此时需要等待adbd重启
                            closePromise.addListener(f1 -> {
                                if (f1.cause() != null) {
                                    promise.tryFailure(f1.cause());
                                } else {
                                    promise.trySuccess(null);
                                }
                            });
                        }
                    }
                });
        return promise;
    }

    @Override
    public Future unroot() {
        Promise promise = new DefaultPromise<>(executor());
        exec("unroot:\0").addListener((Future<String> f) -> {
                    if (f.cause() != null) {
                        promise.tryFailure(f.cause());
                    } else {
                        String s = StringUtils.trim(f.getNow());
                        if (s.equals("adbd not running as root")) {
                            promise.trySuccess(null);
                        } else {
                            //此时需要等待adbd重启
                            closePromise.addListener(f1 -> {
                                if (f1.cause() != null) {
                                    promise.tryFailure(f1.cause());
                                } else {
                                    promise.trySuccess(null);
                                }
                            });
                        }
                    }
                });
        return promise;
    }

    @Override
    public Future remount() {
        Promise promise = new DefaultPromise<>(executor());
        exec("unroot:\0").addListener((Future<String> f) -> {
                    if (f.cause() != null) {
                        promise.tryFailure(f.cause());
                    } else {
                        String s = StringUtils.trim(f.getNow());
                        if (s.equals("remount succeeded")) {
                            promise.trySuccess(null);
                        } else {
                            promise.tryFailure(new RemoteException(s));
                        }
                    }
                });
        return promise;
    }

    private static void assertResult(String result) throws RemoteException {
        if (result.startsWith("FAIL")) {
            int len = Integer.valueOf(result.substring(4, 8), 16);
            throw new RemoteException(result.substring(8, 8 + len));
        } else if (!"OKAY".equals(result)) {
            throw new RemoteException("unknown reply: " + result);
        }
    }

    @Override
    public Future reverse(String destination, AdbChannelInitializer initializer) {
        String cmd = "reverse:forward:" + destination + ";" + destination + "\0";
        Promise promise = new DefaultPromise<>(executor());
        exec(cmd).addListener((Future<String> f) -> {
                    if (f.cause() != null) {
                        promise.tryFailure(f.cause());
                    } else {
                        try {
                            String s = StringUtils.trim(f.getNow());
                            assertResult(s);
                            reverseMap.put(destination, initializer);
                            promise.trySuccess(null);
                        } catch (Throwable cause) {
                            promise.tryFailure(cause);
                        }
                    }
                });
        return promise;
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
        Promise promise = new DefaultPromise<>(executor());
        exec(cmd).addListener((Future<String> f) -> {
                    if (f.cause() != null) {
                        promise.tryFailure(f.cause());
                    } else {
                        try {
                            String s = StringUtils.trim(f.getNow());
                            assertResult(s);
                            reverseMap.put(local, new TCPReverse(host, port, executor()));
                            promise.trySuccess(null);
                        } catch (Throwable cause) {
                            promise.tryFailure(cause);
                        }
                    }
                });
        return promise;
    }

    @Override
    public Future<String[]> reverseList() {
        Promise<String[]> promise = new DefaultPromise<>(executor());
        exec("reverse:list-forward\0").addListener((Future<String> f) -> {
                    if (f.cause() != null) {
                        promise.tryFailure(f.cause());
                    } else {
                        try {
                            String s = StringUtils.trim(f.getNow());
                            assertResult(s);
                            String[] revs;
                            if (StringUtils.isEmpty(s)) {
                                revs = ArrayUtils.EMPTY_STRING_ARRAY;
                            } else {
                                revs = s.split("\r\n|\n|\r");
                            }
                            promise.trySuccess(revs);
                        } catch (Throwable cause) {
                            promise.tryFailure(cause);
                        }
                    }
                });
        return promise;
    }

    @Override
    public Future reverseRemove(String destination) {
        Promise promise = new DefaultPromise<>(executor());
        exec("reverse:killforward:" + destination + "\0").addListener((Future<String> f) -> {
                    if (f.cause() != null) {
                        promise.tryFailure(f.cause());
                    } else {
                        try {
                            String s = StringUtils.trim(f.getNow());
                            assertResult(s);
                            reverseMap.remove(destination);
                            promise.trySuccess(null);
                        } catch (Throwable cause) {
                            promise.tryFailure(cause);
                        }
                    }
                });
        return promise;
    }

    @Override
    public Future reverseRemoveAll() {
        Promise promise = new DefaultPromise<>(executor());
        exec("reverse:killforward-all\0").addListener((Future<String> f) -> {
                    if (f.cause() != null) {
                        promise.tryFailure(f.cause());
                    } else {
                        try {
                            String s = StringUtils.trim(f.getNow());
                            assertResult(s);
                            reverseMap.clear();
                            promise.trySuccess(null);
                        } catch (Throwable cause) {
                            promise.tryFailure(cause);
                        }
                    }
                });
        return promise;
    }

    @Override
    public Future close() {
        connectPromise.addListener((Future<Channel> f) -> {
            if (f.cause() != null) {
                closePromise.trySuccess(null);
            } else {
                f.getNow().close().addListener(f1 -> {
                    if (f1.cause() != null) {
                        closePromise.tryFailure(f1.cause());
                    } else {
                        closePromise.trySuccess(null);
                    }
                });
            }
        });
        //如果调用了close，则强制取消连接动作
        connectPromise.cancel(true);
        return closePromise;
    }

    @Override
    public String toString() {
        return serial;
    }
}
