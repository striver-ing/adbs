package adbs.device;

import adbs.channel.AdbChannelInitializer;
import adbs.constant.DeviceType;
import adbs.constant.Feature;
import adbs.entity.sync.SyncDent;
import adbs.entity.sync.SyncStat;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.DefaultAttributeMap;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;
import org.apache.commons.lang3.StringUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Set;

public class SmartSocketAdbDevice extends DefaultAttributeMap implements AdbDevice {

    private final String host;

    private final Integer port;

    private final NioEventLoopGroup executors;

    private volatile AdbDevice device;

    private volatile boolean isClosed;

    private volatile int readTimeout;

    public SmartSocketAdbDevice(String host, Integer port, RSAPrivateCrtKey privateKey, byte[] publicKey) {
        this.host = host;
        this.port = port;
        this.executors = new NioEventLoopGroup(1, r -> {
            return new Thread(r, "Connection-" + host + ":" + port);
        });
        this.isClosed = false;
        this.device = new ActualSocketDevice(host, port, privateKey, publicKey);
        this.readTimeout = 0;
    }

    public void readTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }

    @Override
    public EventLoop executor() {
        return executors.next();
    }

    @Override
    public boolean isClosed() {
        return isClosed;
    }

    @Override
    public String serial() {
        return device.serial();
    }

    @Override
    public DeviceType type() {
        return device.type();
    }

    @Override
    public String model() {
        return device.model();
    }

    @Override
    public String product() {
        return device.product();
    }

    @Override
    public String device() {
        return device.device();
    }

    @Override
    public Set<Feature> features() {
        return device.features();
    }

    @Override
    public Future<Channel> open(String destination, AdbChannelInitializer initializer) {
        return device.open(destination, initializer);
    }

    @Override
    public Future<String> exec(String destination) {
        return device.exec(destination);
    }

    @Override
    public Future<String> shell(String cmd, String... args) {
        return device.shell(cmd, args);
    }

    @Override
    public Future<Channel> shell(boolean lineFramed, ChannelInboundHandler handler) {
        return device.shell(lineFramed, handler);
    }

    @Override
    public Future<Channel> shell(String cmd, String[] args, boolean lineFramed, ChannelInboundHandler handler) {
        return device.shell(cmd, args, lineFramed, handler);
    }

    @Override
    public Future<SyncStat> stat(String path) {
        return device.stat(path);
    }

    @Override
    public Future<SyncDent[]> list(String path) {
        return device.list(path);
    }

    @Override
    public Future pull(String src, OutputStream dest) {
        return device.pull(src, dest);
    }

    @Override
    public Future push(InputStream src, String dest, int mode, int mtime) {
        return device.push(src, dest, mode, mtime);
    }

    @Override
    public Future root() {
        return device.root();
    }

    @Override
    public Future unroot() {
        return device.unroot();
    }

    @Override
    public Future remount() {
        return device.remount();
    }

    @Override
    public Future reload(int port) {
        return device.reload(port);
    }

    @Override
    public Future reverse(String destination, AdbChannelInitializer initializer) {
        return device.reverse(destination, initializer);
    }

    @Override
    public Future reverse(String remote, String local) {
        return device.reverse(remote, local);
    }

    @Override
    public Future<String[]> reverseList() {
        return device.reverseList();
    }

    @Override
    public Future reverseRemove(String destination) {
        return device.reverseRemove(destination);
    }

    @Override
    public Future reverseRemoveAll() {
        return device.reverseRemoveAll();
    }

    @SuppressWarnings("Duplicates")
    @Override
    public Future close() {
        this.isClosed = true;
        Promise promise = new DefaultPromise(GlobalEventExecutor.INSTANCE);
        device.close().addListener(f0 -> {
            executors.shutdownGracefully().addListener(f1 -> {
                if (f0.cause() == null && f1.cause() == null) {
                    promise.trySuccess(null);
                } else if (f0.cause() != null) {
                    promise.tryFailure(f0.cause());
                } else {
                    promise.tryFailure(f1.cause());
                }
            });
        });
        return promise;
    }

    private class ActualSocketDevice extends AbstractAdbDevice {

        public ActualSocketDevice(String host, Integer port, RSAPrivateCrtKey privateKey, byte[] publicKey) {
            super(host + ":" + port, privateKey, publicKey);
            connect();
        }

        @Override
        protected int readTimeout() {
            return SmartSocketAdbDevice.this.readTimeout;
        }

        @Override
        public EventLoop executor() {
            return SmartSocketAdbDevice.this.executor();
        }

        @Override
        protected ChannelFuture newChannel(AdbChannelInitializer initializer) {
            Bootstrap bootstrap = new Bootstrap();
            return bootstrap.group(executors)
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
                            initializer.initChannel(ch);
                            ch.pipeline().addBefore("codec", "reconnect", new ChannelInboundHandlerAdapter(){
                                @Override
                                public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                    ctx.fireChannelInactive();
                                    if (!SmartSocketAdbDevice.this.isClosed()) {
                                        device = new ActualSocketDevice(host, port, privateKey(), publicKey());
                                    }
                                }
                            });
                        }
                    })
                    .connect(host, port)
                    .addListener(f -> {
                        if (f.cause() != null && !SmartSocketAdbDevice.this.isClosed()) {
                            device = new ActualSocketDevice(host, port, privateKey(), publicKey());
                        }
                    });
        }

        @SuppressWarnings("Duplicates")
        @Override
        public Future reload(int port) {
            Promise promise = new DefaultPromise<>(executor());
            exec("tcpip:"+port+"\0").addListener((Future<String> f) -> {
                        if (f.cause() != null) {
                            promise.tryFailure(f.cause());
                        } else {
                            String s = StringUtils.trim(f.getNow());
                            if (s.startsWith("restarting in TCP mode")) {
                                promise.trySuccess(null);
                            } else {
                                //此时需要等待adbd重启
                                closeFuture().addListener(f1 -> {
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
    }
}
