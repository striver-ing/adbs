package adbs.device;

import adbs.channel.AdbChannelInitializer;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;
import org.apache.commons.lang3.StringUtils;

import java.security.interfaces.RSAPrivateCrtKey;
import java.util.concurrent.TimeUnit;

import static adbs.constant.Constants.DEFAULT_READ_TIMEOUT;

public class SocketAdbDevice extends AbstractAdbDevice {

    private final String host;

    private final Integer port;

    private final EventLoopGroup executors;

    public SocketAdbDevice(String host, Integer port, RSAPrivateCrtKey privateKey, byte[] publicKey) {
        super(host + ":" + port, privateKey, publicKey);
        this.host = host;
        this.port = port;
        this.executors = new NioEventLoopGroup(1, r -> {
            return new Thread(r, "Connection-" + host + ":" + port);
        });
        this.connect();
    }

    public String host() {
        return host;
    }

    public Integer port() {
        return port;
    }

    @Override
    public EventLoop executor() {
        return executors.next();
    }

    protected EventLoopGroup executors() {
        return executors;
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
                    }
                })
                .connect(host, port);
    }

    @Override
    public Future reload(int port) {
        Promise promise = new DefaultPromise<>(executor());
        exec("tcpip:"+port+"\0", DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS)
                .addListener((Future<String> f) -> {
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

    @Override
    public Future close() {
        Promise promise = new DefaultPromise(GlobalEventExecutor.INSTANCE);
        super.close().addListener(f0 -> {
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
    
}
