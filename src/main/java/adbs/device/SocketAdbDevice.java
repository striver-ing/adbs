package adbs.device;

import adbs.util.ChannelFactory;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Future;

import java.security.interfaces.RSAPrivateCrtKey;

@SuppressWarnings({"rawtypes", "unchecked"})
public class SocketAdbDevice extends AbstractAdbDevice {

    private final String host;

    private final Integer port;

    public SocketAdbDevice(String host, Integer port, RSAPrivateCrtKey privateKey, byte[] publicKey) {
        super(host + ":" + port, privateKey, publicKey, new SocketChannelFactory(host, port));
        this.host = host;
        this.port = port;
    }

    public String host() {
        return host;
    }

    public Integer port() {
        return port;
    }

    @Override
    protected boolean autoReconnect() {
        return true;
    }

    @Override
    public Future reload(int port) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Future close() {
        SocketChannelFactory factory = (SocketChannelFactory) factory();
        return super.close().addListener(f -> {
            factory.eventLoop.shutdownGracefully();
        });
    }

    private static class SocketChannelFactory implements ChannelFactory {

        private final String host;

        private final int port;

        private final EventLoopGroup eventLoop;

        public SocketChannelFactory(String host, int port) {
            this.host = host;
            this.port = port;
            this.eventLoop = new NioEventLoopGroup(1, r -> {
                return new Thread(r, "AdbThread-" + host + ":" + port);
            });
        }

        @Override
        public ChannelFuture newChannel(ChannelInitializer<Channel> initializer) {
            Bootstrap bootstrap = new Bootstrap();
            return bootstrap.group(eventLoop)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_LINGER, 3)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .option(ChannelOption.AUTO_CLOSE, true)
                    .handler(initializer)
                    .connect(host, port);
        }
    }
}
