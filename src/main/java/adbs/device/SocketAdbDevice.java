package adbs.device;

import adbs.channel.*;
import adbs.codec.AdbByteArrayEncoder;
import adbs.codec.AdbStringEncoder;
import adbs.connection.AdbAuthHandler;
import adbs.connection.AdbChannelProcessor;
import adbs.connection.AdbPacketCodec;
import adbs.constant.Command;
import adbs.constant.DeviceType;
import adbs.constant.Feature;
import adbs.entity.AdbPacket;
import adbs.entity.ConnectResult;
import adbs.exception.RemoteException;
import adbs.util.AuthUtil;
import com.google.common.util.concurrent.SettableFuture;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
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
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static adbs.constant.Constants.DEFAULT_READ_TIMEOUT;

public class SocketAdbDevice implements AdbDevice {

    private static final Logger logger = LoggerFactory.getLogger(SocketAdbDevice.class);

    private final String serial;

    private final String host;

    private final Integer port;

    private final RSAPrivateCrtKey privateKey;

    private final byte[] publicKey;

    private final Map<Integer, FutureTransportFactory> channelFactoryMap;

    private final Map<CharSequence, TransportFactory> reverseFactoryMap;

    private final AtomicInteger channelIdGen;

    private final ReentrantReadWriteLock lock;

    //设备连接信息
    private volatile NioEventLoopGroup executors;

    private volatile DeviceType type;

    private volatile String model;

    private volatile String product;

    private volatile String device;

    private volatile Set<Feature> features;

    private volatile Channel connection;

    public SocketAdbDevice(String host, Integer port, RSAPrivateCrtKey privateKey, byte[] publicKey) throws IOException {
        this.serial = host + ":" + port;
        this.host = host;
        this.port = port;
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.channelFactoryMap = new ConcurrentHashMap<>();
        this.reverseFactoryMap = new ConcurrentHashMap<>();
        this.channelIdGen = new AtomicInteger(1);
        this.lock = new ReentrantReadWriteLock();
        ConnectResult result = connect();
        this.initConnect(result);
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

    private ConnectResult connect() throws IOException {
        Bootstrap bootstrap = new Bootstrap();
        SettableFuture<ConnectResult> future = SettableFuture.create();
        NioEventLoopGroup eventLoops = new NioEventLoopGroup(1, r -> {
            return new Thread(r, "Connection-" + serial);
        });
        try {
            Channel channel = bootstrap.group(eventLoops)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_LINGER, 3)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline()
                                    .addLast(new AdbPacketCodec())
                                    .addLast(new AdbAuthHandler(future, privateKey, publicKey))
                                    .addLast(new AdbChannelProcessor(SocketAdbDevice.this, channelIdGen, channelFactoryMap, reverseFactoryMap));
                        }
                    })
                    .connect(host, port)
                    .sync()
                    .channel();
            ConnectResult result = future.get(DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
            result.setExecutors(eventLoops);
            result.setChannel(channel);
            return result;
        } catch (Throwable cause) {
            //发生异常时把线程关掉
            eventLoops.shutdownGracefully();
            throw new IOException("Connect to " + host + ":" + port + " failed: " + cause.getMessage(), cause);
        }
    }

    protected void initConnect(ConnectResult result) {
        this.type = result.getType();
        this.model = result.getModel();
        this.product = result.getProduct();
        this.device = result.getDevice();
        this.features = result.getFeatures();
        this.connection = result.getChannel();
        this.executors = result.getExecutors();
    }

    @Override
    public <I, O> Transport<I, O> open(String destination, long timeout, TimeUnit unit, FutureTransportFactory<I, O> factory) throws IOException {
        this.lock.readLock().lock();
        try {
            int localId = channelIdGen.getAndIncrement();
            ByteBuf buf = null;
            try {
                byte[] bytes = destination.getBytes(StandardCharsets.UTF_8);
                buf = connection.alloc().buffer(bytes.length);
                buf.writeBytes(bytes);
                channelFactoryMap.put(localId, factory);
                connection.writeAndFlush(new AdbPacket(Command.A_OPEN, localId, 0, buf))
                        .addListener(f -> {
                            if (f.cause() != null) {
                                factory.future().setException(f.cause());
                            }
                        });
                return factory.future().get(timeout, unit);
            } catch (Throwable cause) {
                if (buf != null && buf.refCnt() > 0) {
                    ReferenceCountUtil.safeRelease(buf);
                }
                channelFactoryMap.remove(localId);
                throw new IOException("Open destination `"+destination+"` failed: " + cause.getMessage(), cause);
            }
        } finally {
            this.lock.readLock().unlock();
        }
    }

    @Override
    public String exec(String destination, long timeout, TimeUnit unit) throws IOException {
        this.lock.readLock().lock();
        try {
            SettableFuture<String> future = SettableFuture.create();
            StringBuilder sb = new StringBuilder();
            Transport<String, String> transport = open(destination, timeout, unit, new FutureTransportFactory<String, String>() {
                @Override
                public Transport<String, String> factory(AdbChannel channel) {
                    return new AbstractTransport<String, String>(channel) {

                        @Override
                        protected void init() {
                            channel.addHandler(new StringDecoder(StandardCharsets.UTF_8))
                                    .addHandler(new StringEncoder(StandardCharsets.UTF_8));
                        }

                        @Override
                        public void onMessage(String message) {
                            sb.append(message);
                        }

                        @Override
                        public void onClose() {
                            future.set(sb.toString());
                        }
                    };
                }
            });
            try {
                return future.get(timeout, unit);
            } catch (Throwable cause) {
                throw new IOException("exec `" + destination + "` failed: " + cause.getMessage(), cause);
            } finally {
                transport.close();
            }
        } finally {
            this.lock.readLock().unlock();
        }
    }

    private void assertExec(String cmd, String... expects) throws IOException {
        String result = exec(cmd, DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
        result = StringUtils.trim(result);
        if (!StringUtils.equalsAny(result, expects)) {
            throw new RemoteException(result);
        }
    }

    @Override
    public void root() throws IOException {
        assertExec("root:\0", "adbd is already running as root", "restarting adbd as root");
    }

    @Override
    public void unroot() throws IOException {
        assertExec("unroot:\0", "adbd not running as root", "restarting adbd as non root");
    }

    @Override
    public void remount() throws IOException {
        assertExec("remount:\0", "remount succeeded");
    }

    @Override
    public void reverse(String destination, TransportFactory factory) throws IOException {
        String cmd = "reverse:forward:" + destination + ";" + destination + "\0";
        String result = exec(cmd, DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
        if (result.startsWith("FAIL")) {
            int len = Integer.valueOf(result.substring(4, 8), 16);
            throw new ProtocolException(result.substring(8, 8 + len));
        }
        reverseFactoryMap.put(destination, factory);
    }

    @Override
    public Future<?> close(boolean autoReconnect) {
        return this.executors.shutdownGracefully().addListener(f -> {
            logger.info("connection `{}:{}` closed", host, port);
            //每个channel都通知一遍
            Iterator<Map.Entry<Integer, FutureTransportFactory>> iterator = channelFactoryMap.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Integer, FutureTransportFactory> entry = iterator.next();
                entry.getValue().future().setException(new Exception("Channel closed"));
                iterator.remove();
            }
            if (autoReconnect) {
                this.lock.writeLock().lock();
                try {
                    while (true) {
                        try {
                            ConnectResult res = connect();
                            this.initConnect(res);
                            logger.info("reconnected to {}:{}", host, port);
                            break;
                        } catch (Throwable cause) {
                            logger.info("try reconnect to {}:{}", host, port);
                        }
                    }
                } finally {
                    this.lock.writeLock().unlock();
                }
            }
        });
    }

    public static void main(String[] args) throws Exception {
        RSAPrivateCrtKey privateKey = AuthUtil.loadPrivateKey("adbkey");
        String publicKey = AuthUtil.generatePublicKey(privateKey);
        TransportFactory<byte[], byte[]> factory = new TransportFactory<byte[], byte[]>() {
            @Override
            public Transport<byte[], byte[]> factory(AdbChannel channel) {
                return new AbstractTransport<byte[], byte[]>(channel) {

                    private Socket socket;

                    private InputStream inputStream;

                    private OutputStream outputStream;

                    @Override
                    protected void init() {
                        channel.addHandler(new ByteArrayDecoder())
                                .addHandler(new AdbByteArrayEncoder(channel));
                    }

                    @Override
                    public void onOpen() {
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
                                                write(b);
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
                    public void onMessage(byte[] message) {
                        try {
                            outputStream.write(message);
                        } catch (Throwable cause) {
                            cause.printStackTrace();
                        }
                    }
                };
            }
        };
        SocketAdbDevice device = new SocketAdbDevice("127.0.0.1", 6000, privateKey, publicKey.getBytes(StandardCharsets.UTF_8)) {
            @Override
            protected void initConnect(ConnectResult result) {
                super.initConnect(result);
                try {
                    reverse("tcp:1234", factory);
                } catch (Throwable cause) {
                    throw new RuntimeException(cause.getMessage(), cause);
                }
            }
        };
        device.unroot();
        device.root();
    }

}
