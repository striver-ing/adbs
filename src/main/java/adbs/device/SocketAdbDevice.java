package adbs.device;

import adbs.util.AuthUtil;
import adbs.util.ChannelFactory;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateCrtKey;

public class SocketAdbDevice extends AbstractAdbDevice {

    private static final Logger logger = LoggerFactory.getLogger(SocketAdbDevice.class);

    private static final RSAPrivateCrtKey privateKey;
    private static final byte[] publicKey;

    static {
        try {
            privateKey = AuthUtil.loadPrivateKey("adbkey");
            publicKey = AuthUtil.generatePublicKey(privateKey).getBytes(StandardCharsets.UTF_8);
        } catch (Throwable cause) {
            throw new RuntimeException("load private key failed:" + cause.getMessage(), cause);
        }
    }

    private final NioEventLoopGroup executors;

    private SocketAdbDevice(String serial, RSAPrivateCrtKey privateKey, byte[] publicKey, NioEventLoopGroup executors, ChannelFactory factory) {
        super(serial, privateKey, publicKey, factory);
        this.executors = executors;
    }

    @Override
    protected void doClose() {
        this.executors.shutdownGracefully().addListener(f -> {
            if (f.cause() == null) {
                logger.info("`{}` executor terminated", serial());
            } else {
                logger.error("`{}` executor terminate error:{}", serial(), f.cause().getMessage(), f.cause());
            }
        });
    }

    public static SocketAdbDevice connect(String host, Integer port) {
        String serial = host + ":" + port;
        NioEventLoopGroup executors = new NioEventLoopGroup(1, r -> {
            return new Thread(r, "Connection-" + serial);
        });
        ChannelFactory factory = new ChannelFactory() {
            @Override
            public ChannelFuture newChannel(AbstractAdbDevice device, ChannelInitializer initializer) {
                Bootstrap bootstrap = new Bootstrap();
                return bootstrap.group(executors)
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
        };
        return new SocketAdbDevice(serial, privateKey, publicKey, executors, factory);
    }

}
