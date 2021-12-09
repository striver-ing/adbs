package adbs.device;

import adbs.channel.AdbChannel;
import adbs.channel.AdbChannelAddress;
import adbs.channel.AdbChannelInitializer;
import adbs.channel.TCPReverse;
import adbs.codec.*;
import adbs.connection.*;
import adbs.constant.Constants;
import adbs.constant.DeviceType;
import adbs.constant.Feature;
import adbs.entity.DeviceInfo;
import adbs.entity.PendingWriteEntry;
import adbs.entity.sync.SyncDent;
import adbs.entity.sync.SyncQuit;
import adbs.entity.sync.SyncStat;
import adbs.exception.RemoteException;
import adbs.util.ChannelFactory;
import adbs.util.ChannelUtil;
import adbs.util.ShellUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.DefaultAttributeMap;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class AbstractAdbDevice extends DefaultAttributeMap implements AdbDevice {

    private static final Logger logger = LoggerFactory.getLogger(AbstractAdbDevice.class);

    private final String serial;

    private final RSAPrivateCrtKey privateKey;

    private final byte[] publicKey;

    private final ChannelFactory factory;

    private final AtomicInteger channelIdGen;

    private final Map<CharSequence, AdbChannelInitializer> reverseMap;

    private final Set<Channel> forwards;

    private volatile boolean autoReconnect;

    private volatile ChannelFuture connectFuture;

    private volatile Channel channel;

    private volatile DeviceInfo deviceInfo;

    protected AbstractAdbDevice(String serial, RSAPrivateCrtKey privateKey, byte[] publicKey, ChannelFactory factory) {
        this.serial = serial;
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.factory = factory;
        this.channelIdGen = new AtomicInteger(1);
        this.reverseMap = new ConcurrentHashMap<>();
        this.forwards = ConcurrentHashMap.newKeySet();
        this.autoReconnect = false;
        this.initConnection();
    }

    private void initConnection() {
        ChannelFuture future = factory.newChannel(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ChannelPipeline pipeline = ch.pipeline();
                pipeline.addLast("reconnect", new AutoReconnectHandler())
                        .addLast("codec", new AdbPacketCodec())
                        .addLast("auth", new AdbAuthHandler(privateKey, publicKey))
                        .addLast("connect", new ConnectHandler());
            }
        });
        future.addListener(f -> {
            if (f.cause() != null) {
                if (autoReconnect) {
                    logger.error("[{}] connect failed, try reconnect, error={}", serial(), f.cause().getMessage(), f.cause());
                    initConnection();
                } else {
                    logger.error("[{}] connect failed, error={}", serial(), f.cause().getMessage(), f.cause());
                }
            } else {
                logger.info("[{}] connect success", serial());
            }
        });
        this.connectFuture = future;
        this.channel = future.channel();
    }

    protected ChannelFactory factory() {
        return factory;
    }

    protected RSAPrivateCrtKey privateKey() {
        return privateKey;
    }

    protected byte[] publicKey() {
        return publicKey;
    }

    @Override
    public EventLoop eventLoop() {
        return channel.eventLoop();
    }

    @Override
    public String serial() {
        return serial;
    }

    @Override
    public DeviceType type() {
        return deviceInfo != null ? deviceInfo.type : null;
    }

    @Override
    public String model() {
        return deviceInfo != null ? deviceInfo.model : null;
    }

    @Override
    public String product() {
        return deviceInfo != null ? deviceInfo.product : null;
    }

    @Override
    public String device() {
        return deviceInfo != null ? deviceInfo.device : null;
    }

    @Override
    public Set<Feature> features() {
        return deviceInfo != null ? deviceInfo.features : null;
    }

    @Override
    public ChannelFuture open(String destination, int timeoutMs, AdbChannelInitializer initializer) {
        int localId = channelIdGen.getAndIncrement();
        String channelName = ChannelUtil.getChannelName(localId);
        AdbChannel adbChannel = new AdbChannel(channel, localId, 0);
        adbChannel.config().setConnectTimeoutMillis(timeoutMs);
        initializer.initChannel(adbChannel);
        channel.pipeline().addLast(channelName, adbChannel);
        return adbChannel.connect(new AdbChannelAddress(destination, localId));
    }

    @Override
    public Future<String> exec(String destination, int timeoutMs) {
        Promise<String> promise = eventLoop().newPromise();
        ChannelFuture future = open(destination, timeoutMs, channel -> {
            channel.pipeline()
                    .addLast(new StringDecoder(StandardCharsets.UTF_8))
                    .addLast(new StringEncoder(StandardCharsets.UTF_8))
                    .addLast(new ExecHandler(promise));
        });
        future.addListener(f -> {
            if (f.cause() != null) {
                promise.tryFailure(f.cause());
            }
        });
        promise.addListener(f -> {
            future.channel().close();
        });
        if (timeoutMs > 0) {
            eventLoop().schedule(() -> {
                TimeoutException cause = new TimeoutException("exec timeout: " + destination.trim());
                promise.tryFailure(cause);
            }, timeoutMs, TimeUnit.MILLISECONDS);
        }

        return promise;
    }

    @Override
    public Future<String> shell(String cmd, int timeoutMs, String... args) {
        String shellCmd = ShellUtil.buildShellCmd(cmd, args);
        return exec(shellCmd, timeoutMs);
    }

    @Override
    public ChannelFuture shell(boolean lineFramed, ChannelInboundHandler handler) {
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
    public ChannelFuture shell(String cmd, String[] args, boolean lineFramed, ChannelInboundHandler handler) {
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

    private <T> void sync(Promise<T> promise, AdbChannelInitializer initializer) {
        ChannelFuture future = open("sync:\0", initializer);
        future.addListener(f -> {
            if (f.cause() != null) {
                promise.tryFailure(f.cause());
            }
        });
        promise.addListener(f -> {
            future.channel()
                    .writeAndFlush(new SyncQuit())
                    .addListener(f0 -> {
                        future.channel().close();
                    });
        });
    }

    @Override
    public Future<SyncStat> stat(String path) {
        Promise<SyncStat> promise = eventLoop().newPromise();
        sync(promise, chl -> {
            chl.pipeline()
                    .addLast(new SyncStatDecoder())
                    .addLast(new SyncEncoder())
                    .addLast(new SyncStatHandler(this, path, promise));
        });
        return promise;
    }

    @Override
    public Future<SyncDent[]> list(String path) {
        Promise<SyncDent[]> promise = eventLoop().newPromise();
        sync(promise, chl -> {
            chl.pipeline()
                    .addLast(new SyncDentDecoder())
                    .addLast(new SyncDentAggregator())
                    .addLast(new SyncEncoder())
                    .addLast(new SyncListHandler(this, path, promise));
        });
        return promise;
    }

    @Override
    public Future pull(String src, OutputStream dest) {
        Promise promise = eventLoop().newPromise();
        sync(promise, chl -> {
            chl.pipeline()
                    .addLast(new SyncDataDecoder())
                    .addLast(new SyncEncoder())
                    .addLast(new SyncPullHandler(this, src, dest, promise));
        });
        return promise;
    }

    @Override
    public Future push(InputStream src, String dest, int mode, int mtime) {
        Promise promise = eventLoop().newPromise();
        sync(promise, chl -> {
            chl.pipeline()
                    .addLast(new SyncDecoder())
                    .addLast(new SyncEncoder())
                    .addLast(new SyncPushHandler(this, src, dest, mode, mtime, promise));
        });
        return promise;
    }


    @Override
    public Future root() {
        Promise promise = eventLoop().newPromise();
        exec("root:\0").addListener((Future<String> f) -> {
                    if (f.cause() != null) {
                        promise.tryFailure(f.cause());
                    } else {
                        String s = StringUtils.trim(f.getNow());
                        if (s.equals("adbd is already running as root")) {
                            promise.trySuccess(null);
                        } else {
                            initConnection();
                            ChannelFuture future = this.connectFuture;
                            future.addListener(f1 -> {
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
        Promise promise = eventLoop().newPromise();
        exec("unroot:\0").addListener((Future<String> f) -> {
                    if (f.cause() != null) {
                        promise.tryFailure(f.cause());
                    } else {
                        String s = StringUtils.trim(f.getNow());
                        if (s.equals("adbd not running as root")) {
                            promise.trySuccess(null);
                        } else {
                            initConnection();
                            ChannelFuture future = this.connectFuture;
                            future.addListener(f1 -> {
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
        Promise promise = eventLoop().newPromise();
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

    private static String readResult(String result) throws RemoteException {
        if (StringUtils.isEmpty(result)) {
            return null;
        } else if (result.startsWith("FAIL")) {
            int len = Integer.valueOf(result.substring(4, 8), 16);
            throw new RemoteException(result.substring(8, 8 + len));
        } else if (result.startsWith("OKAY")) {
            if (result.length() > 4) {
                int len = Integer.valueOf(result.substring(4, 8), 16);
                return result.substring(8, 8 + len);
            } else {
                return null;
            }
        } else {
            int len = Integer.valueOf(result.substring(0, 4), 16);
            return result.substring(4, 4 + len);
        }
    }

    @Override
    public Future<String> reverse(String destination, AdbChannelInitializer initializer) {
        String cmd = "reverse:forward:" + destination + ";" + destination + "\0";
        Promise promise = eventLoop().newPromise();
        exec(cmd).addListener((Future<String> f) -> {
                    if (f.cause() != null) {
                        promise.tryFailure(f.cause());
                    } else {
                        try {
                            String result = readResult(f.getNow());
                            reverseMap.put(destination, initializer);
                            promise.trySuccess(result);
                        } catch (Throwable cause) {
                            promise.tryFailure(cause);
                        }
                    }
                });
        return promise;
    }

    @Override
    public Future<String> reverse(String remote, String local) {
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
        Promise<String> promise = eventLoop().newPromise();
        exec(cmd).addListener((Future<String> f) -> {
                    if (f.cause() != null) {
                        promise.tryFailure(f.cause());
                    } else {
                        try {
                            String result = readResult(f.getNow());
                            reverseMap.put(local, new TCPReverse(host, port, eventLoop()));
                            promise.trySuccess(result);
                        } catch (Throwable cause) {
                            promise.tryFailure(cause);
                        }
                    }
                });
        return promise;
    }

    @Override
    public Future<String[]> reverseList() {
        Promise<String[]> promise = eventLoop().newPromise();
        exec("reverse:list-forward\0").addListener((Future<String> f) -> {
                    if (f.cause() != null) {
                        promise.tryFailure(f.cause());
                    } else {
                        try {
                            String result = StringUtils.trim(readResult(f.getNow()));
                            String[] revs;
                            if (StringUtils.isEmpty(result)) {
                                revs = ArrayUtils.EMPTY_STRING_ARRAY;
                            } else {
                                revs = result.split("\r\n|\n|\r");
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
        Promise promise = eventLoop().newPromise();
        exec("reverse:killforward:" + destination + "\0").addListener((Future<String> f) -> {
                    if (f.cause() != null) {
                        promise.tryFailure(f.cause());
                    } else {
                        try {
                            String result = readResult(f.getNow());
                            reverseMap.remove(destination);
                            promise.trySuccess(result);
                        } catch (Throwable cause) {
                            promise.tryFailure(cause);
                        }
                    }
                });
        return promise;
    }

    @Override
    public Future reverseRemoveAll() {
        Promise promise = eventLoop().newPromise();
        exec("reverse:killforward-all\0").addListener((Future<String> f) -> {
                    if (f.cause() != null) {
                        promise.tryFailure(f.cause());
                    } else {
                        try {
                            String result = readResult(f.getNow());
                            reverseMap.clear();
                            promise.trySuccess(result);
                        } catch (Throwable cause) {
                            promise.tryFailure(cause);
                        }
                    }
                });
        return promise;
    }

    @Override
    public ChannelFuture forward(String destination, int port) {
        ServerBootstrap bootstrap = new ServerBootstrap();
        return bootstrap.group(eventLoop())
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {

                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {

                        ChannelFuture future = open(destination, 30000, chl -> {
                            chl.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                    ch.writeAndFlush(msg);
                                }

                                @Override
                                public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                    ch.close();
                                }
                            });
                        }).addListener(f -> {
                            if (f.cause() != null) {
                                logger.error("open destination `{}` failed, error={}", destination, f.cause().getMessage(), f.cause());
                                ch.close();
                            }
                        });
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter(){
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                future.channel().writeAndFlush(msg);
                            }

                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                future.channel().close();
                            }
                        });
                    }
                })
                .bind(port)
                .addListener((ChannelFuture f) -> {
                    if (f.cause() != null) {
                        forwards.add(f.channel());
                    }
                });
    }

    @Override
    public void setAutoReconnect(boolean autoReconnect) {
        this.autoReconnect = autoReconnect;
    }

    @Override
    public void close() {
        setAutoReconnect(false);
        try {
            //关闭reverse
            reverseRemoveAll().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("[{}] remove reverse failed", serial(), e);
        }
        //关闭forward
        for (Channel forward : forwards) {
            try {
                forward.close().get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.error("[{}] remove forward failed, channel={}", serial(), forward, e);
            }
        }
        try {
            channel.close().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("disconnect failed", e);
        }
    }

    @Override
    public String toString() {
        return serial;
    }

    private class AutoReconnectHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (autoReconnect) {
                logger.error("[{}] connection disconnected, try reconnect", serial());
                initConnection();
            } else {
                logger.info("[{}] connection disconnected", serial());
            }
            super.channelInactive(ctx);
        }
    }

    private class ConnectHandler extends ChannelDuplexHandler {

        private final Queue<PendingWriteEntry> pendingWriteEntries = new LinkedList<>();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof DeviceInfo) {
                ctx.pipeline()
                        .remove(this)
                        .addAfter("codec", "processor", new AdbChannelProcessor(channelIdGen, reverseMap));
                AbstractAdbDevice.this.deviceInfo = (DeviceInfo) msg;
                while (true) {
                    PendingWriteEntry entry = pendingWriteEntries.poll();
                    if (entry == null) {
                        break;
                    }
                    ctx.channel().write(entry.msg).addListener(f -> {
                        if (f.cause() != null) {
                            entry.promise.tryFailure(f.cause());
                        } else {
                            entry.promise.trySuccess();
                        }
                    });
                }
                ctx.channel().flush();
            } else {
                super.channelRead(ctx, msg);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            ctx.close();
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (!pendingWriteEntries.offer(new PendingWriteEntry(msg, promise))) {
                promise.tryFailure(new RejectedExecutionException("queue is full"));
            }
        }
    }
}
