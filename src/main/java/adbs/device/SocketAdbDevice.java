package adbs.device;

import adbs.channel.AdbChannel;
import adbs.channel.AdbChannelAddress;
import adbs.channel.AdbChannelInitializer;
import adbs.connection.AdbAuthHandler;
import adbs.connection.AdbChannelProcessor;
import adbs.connection.AdbPacketCodec;
import adbs.constant.DeviceType;
import adbs.constant.Feature;
import adbs.entity.ConnectResult;
import adbs.entity.sync.SyncDent;
import adbs.entity.sync.SyncStat;
import adbs.exception.RemoteException;
import adbs.feature.AdbShell;
import adbs.feature.AdbSync;
import adbs.feature.impl.AdbShellImpl;
import adbs.feature.impl.AdbSyncImpl;
import adbs.util.AuthUtil;
import adbs.util.ChannelUtil;
import com.google.common.util.concurrent.SettableFuture;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProtocolException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static adbs.constant.Constants.DEFAULT_READ_TIMEOUT;

public class SocketAdbDevice implements AdbDevice {

    private static final Logger logger = LoggerFactory.getLogger(SocketAdbDevice.class);

    private final String serial;

    private final String host;

    private final Integer port;

    private final RSAPrivateCrtKey privateKey;

    private final byte[] publicKey;

    private final Map<CharSequence, AdbChannelInitializer> reverseMap;

    private final AtomicInteger channelIdGen;

    private final NioEventLoopGroup executors;

    private final AdbSync sync;

    private final AdbShell shell;

    //设备连接信息
    private volatile DeviceType type;

    private volatile String model;

    private volatile String product;

    private volatile String device;

    private volatile Set<Feature> features;

    protected volatile Channel connection;

    public SocketAdbDevice(String host, Integer port, RSAPrivateCrtKey privateKey, byte[] publicKey) throws IOException {
        this.serial = host + ":" + port;
        this.host = host;
        this.port = port;
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.reverseMap = new ConcurrentHashMap<>();
        this.channelIdGen = new AtomicInteger(1);
        this.executors = new NioEventLoopGroup(1, r -> {
            return new Thread(r, "Connection-" + serial);
        });
        this.sync = new AdbSyncImpl(this);
        this.shell = new AdbShellImpl(this);
        try {
            ConnectResult result = connect();
            this.initConnect(result);
        } catch (Throwable cause) {
            this.executors.shutdownGracefully();
            throw new IOException("Connect to " + host + ":" + port + " failed: " + cause.getMessage(), cause);
        }
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

    private ConnectResult connect() throws Exception {
        Bootstrap bootstrap = new Bootstrap();
        SettableFuture<ConnectResult> future = SettableFuture.create();
        Channel channel = bootstrap.group(executors)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_LINGER, 3)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.AUTO_CLOSE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline()
                                .addLast(new AdbPacketCodec())
                                .addLast(new AdbAuthHandler(future, privateKey, publicKey))
                                .addLast("handler", new AdbChannelProcessor(SocketAdbDevice.this, channelIdGen, reverseMap));
                    }
                })
                .connect(host, port)
                .sync()
                .channel();
        ConnectResult result = future.get(DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
        result.setChannel(channel);
        return result;
    }

    protected void initConnect(ConnectResult result) {
        this.type = result.getType();
        this.model = result.getModel();
        this.product = result.getProduct();
        this.device = result.getDevice();
        this.features = result.getFeatures();
        this.connection = result.getChannel();
    }

    @Override
    public ChannelFuture open(String destination, AdbChannelInitializer initializer) throws IOException {
        int localId = channelIdGen.getAndIncrement();
        String channelName = ChannelUtil.getChannelName(localId);
        AdbChannel channel = new AdbChannel(connection, localId, 0);
        initializer.initChannel(channel);
        connection.pipeline().addLast(channelName, channel);
        try {
            return channel.connect(new AdbChannelAddress(destination, localId));
        } catch (Throwable cause) {
            connection.pipeline().remove(channelName);
            throw new IOException("Open destination `"+destination+"` failed: " + cause.getMessage(), cause);
        }
    }

    @Override
    public String exec(String destination, long timeout, TimeUnit unit) throws IOException {
        SettableFuture<String> future = SettableFuture.create();
        StringBuilder sb = new StringBuilder();
        ChannelFuture cf = open(destination, channel -> {
            channel.pipeline()
                    .addLast(new StringDecoder(StandardCharsets.UTF_8))
                    .addLast(new StringEncoder(StandardCharsets.UTF_8))
                    .addLast(new ChannelInboundHandlerAdapter(){
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
                            future.set(sb.toString());
                        }
                    });
        });
        try {
            return future.get(timeout, unit);
        } catch (Throwable cause) {
            throw new IOException("exec `" + destination + "` failed: " + cause.getMessage(), cause);
        } finally {
            cf.channel().close();
        }
    }

    private static void assertResult(String result) throws IOException {
        if (result.startsWith("FAIL")) {
            int len = Integer.valueOf(result.substring(4, 8), 16);
            throw new RemoteException(result.substring(8, 8 + len));
        } else if (!"OKAY".equals(result)) {
            throw new ProtocolException("unknown reply: " + result);
        }
    }

    private Future addReconnectHandler() {
        SettableFuture future = SettableFuture.create();
        connection.pipeline().addAfter("handler", "reconnect", new ChannelInboundHandlerAdapter(){
            @Override
            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                ctx.fireChannelInactive();
                future.set(null);
            }
        });
        return future;
    }

    private void waitForReconnect(Future future) throws IOException {
        //等待adb daemon重启
        try {
            future.get(DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
        } catch (Throwable cause) {
            throw new IOException("reconnect to `" + host + ":" + port + "` failed:" + cause.getMessage(), cause);
        }
        while (true) {
            try {
                ConnectResult connectResult = this.connect();
                this.initConnect(connectResult);
                logger.info("reconnected to `{}:{}`", host, port);
                break;
            } catch (Throwable cause) {
                logger.error("reconnect to `{}:{}` failed:{}", host, port, cause.getMessage());
            }
        }
    }

    @Override
    public void root() throws IOException {
        Future future = addReconnectHandler();
        String result = exec("root:\0", DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
        result = StringUtils.trim(result);
        if ("restarting adbd as root".equals(result)) {
            waitForReconnect(future);
        } else if ("adbd is already running as root".equals(result)) {
            connection.pipeline().remove("reconnect");
        } else {
            waitForReconnect(future);
        }
    }

    @Override
    public void unroot() throws IOException {
        Future future = addReconnectHandler();
        String result = exec("unroot:\0", DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
        result = StringUtils.trim(result);
        if ("restarting adbd as non root".equals(result)) {
            waitForReconnect(future);
        } else if ("adbd not running as root".equals(result)) {
            connection.pipeline().remove("reconnect");
        } else {
            waitForReconnect(future);
        }
    }

    @Override
    public void remount() throws IOException {
        String result = exec("remount:\0", DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
        result = StringUtils.trim(result);
        if (!"remount succeeded".equals(result)) {
            throw new RemoteException(result);
        }
    }

    @Override
    public SyncStat stat(String path) throws IOException {
        return sync.stat(path);
    }

    @Override
    public SyncDent[] list(String path) throws IOException {
        return sync.list(path);
    }

    @Override
    public int pull(String src, OutputStream dest) throws IOException {
        return sync.pull(src, dest);
    }

    @Override
    public int push(InputStream src, String dest, int mode, int mtime) throws IOException {
        return sync.push(src, dest, mode, mtime);
    }

    @Override
    public String exec(String cmd, String... args) throws IOException {
        return shell.exec(cmd, args);
    }

    @Override
    public ChannelFuture shell(boolean lineFramed, ChannelInboundHandler handler) throws IOException {
        return shell.shell(lineFramed, handler);
    }

    @Override
    public void reverse(String destination, AdbChannelInitializer initializer) throws IOException {
        String cmd = "reverse:forward:" + destination + ";" + destination + "\0";
        String result = exec(cmd, DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
        assertResult(result);
        reverseMap.put(destination, initializer);
    }

    @Override
    public List<String> reverseList() throws IOException {
        String result = exec("reverse:list-forward\0", DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
        int len = Integer.valueOf(result.substring(0, 4), 16);
        //-1是因为最后有一个\0
        result = result.substring(4, len + 4 - 1);
        String[] lines = result.split("\n");
        return Arrays.stream(lines)
                .map(line -> StringUtils.trim(line))
                .filter(line -> StringUtils.isNotEmpty(line))
                .collect(Collectors.toList());
    }

    @Override
    public void reverseRemove(String destination) throws IOException {
        String result = exec("reverse:killforward:" + destination + "\0", DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
        assertResult(result);
        reverseMap.remove(destination);
    }

    @Override
    public void reverseRemoveAll() throws IOException {
        String result = exec("reverse:killforward-all\0", DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
        assertResult(result);
        reverseMap.clear();
    }

    @Override
    public io.netty.util.concurrent.Future<?> close() {
        return this.executors.shutdownGracefully().addListener(f -> {
            if (f.cause() == null) {
                logger.info("connection `{}:{}` closed", host, port);
            } else {
                logger.error("connection `{}:{}` close error:{}", host, port, f.cause().getMessage(), f.cause());
            }
        });
    }

    public static void main(String[] args) throws Exception {
        RSAPrivateCrtKey privateKey = AuthUtil.loadPrivateKey("adbkey");
        String publicKey = AuthUtil.generatePublicKey(privateKey);
        AdbChannelInitializer initializer = new AdbChannelInitializer() {
            @Override
            public void initChannel(Channel channel) {
                channel.pipeline()
                        .addLast(new ByteArrayDecoder())
                        .addLast(new ByteArrayEncoder())
                        .addLast(new ChannelInboundHandlerAdapter(){

                            private Socket socket;

                            private InputStream inputStream;

                            private OutputStream outputStream;

                            @Override
                            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                                try {
                                    socket = new Socket("127.0.0.1", 8889);
                                    inputStream = socket.getInputStream();
                                    outputStream = socket.getOutputStream();
                                    new Thread() {
                                        @Override
                                        public void run() {
                                            while (true) {
                                                try {
                                                    byte[] b = new byte[4096];
                                                    int size = inputStream.read(b);
                                                    if (size == -1) {
                                                        break;
                                                    }
                                                    if (size > 0) {
                                                        b = Arrays.copyOfRange(b, 0, size);
                                                        ctx.writeAndFlush(b);
                                                    }
                                                } catch (Throwable cause) {
                                                    cause.printStackTrace();
                                                }
                                            }
                                        }
                                    }.start();
                                } catch (Throwable cause) {
                                    cause.printStackTrace();
                                }
                            }

                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                try {
                                    byte[] bytes = (byte[]) msg;
                                    outputStream.write(bytes);
                                } catch (Throwable cause) {
                                    cause.printStackTrace();
                                }
                            }
                        });
            }
        };
        SocketAdbDevice device = new SocketAdbDevice("127.0.0.1", 6000, privateKey, publicKey.getBytes(StandardCharsets.UTF_8));
        SyncStat stat = device.stat("/sdcard/base.apk2");
        System.out.println(stat);
//        //System.out.println(device.shell("ls", "-l", "/"));
//        device.root();
//        System.out.println("rooted");
//        device.unroot();
//        System.out.println("unrooted");
//        device.close();
//        String result = device.exec("shell:ls -l /\0", 30, TimeUnit.SECONDS);
//        System.out.println(result);
//        SocketAdbDevice device = new SocketAdbDevice("127.0.0.1", 6000, privateKey, publicKey.getBytes(StandardCharsets.UTF_8)) {
//            @Override
//            protected void initConnect(ConnectResult result) {
//                super.initConnect(result);
//                try {
//                    reverse("tcp:1234", initializer);
//                } catch (Throwable cause) {
//                    throw new RuntimeException(cause.getMessage(), cause);
//                }
//            }
//        };
    }

}
