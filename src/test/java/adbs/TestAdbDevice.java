package adbs;

import adbs.channel.AdbChannelInitializer;
import adbs.connection.AdbAuthHandler;
import adbs.connection.AdbPacketCodec;
import adbs.device.AbstractAdbDevice;
import adbs.device.SocketAdbDevice;
import adbs.entity.ConnectResult;
import adbs.util.AuthUtil;
import adbs.util.ChannelFactory;
import com.google.common.util.concurrent.SettableFuture;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Promise;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Arrays;

public class TestAdbDevice {

    private final static AdbChannelInitializer adbInitializer = new AdbChannelInitializer() {
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

    private static class CloudAdbDevice extends AbstractAdbDevice {

        public CloudAdbDevice(String serial, RSAPrivateCrtKey privateKey, byte[] publicKey, ChannelFactory factory) throws IOException {
            super(serial, privateKey, publicKey, factory);
        }

        @Override
        protected void doClose() {

        }

        public static CloudAdbDevice connect(String host, Integer port) throws Exception {
            String serial = host + ":" + port;
            NioEventLoopGroup executors = new NioEventLoopGroup(1, r -> {
                return new Thread(r, "Connection-" + serial);
            });
            RSAPrivateCrtKey privateKey = AuthUtil.loadPrivateKey("adbkey");
            byte[] publicKey = AuthUtil.generatePublicKey(privateKey).getBytes(StandardCharsets.UTF_8);
            ChannelFactory factory = new ChannelFactory() {
                @Override
                public ChannelFuture newChannel(AbstractAdbDevice device, ChannelInitializer<Channel> initializer) {

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
                                    ch.pipeline()
                                            .addLast(new AdbPacketCodec())
                                            .addLast(new AdbAuthHandler(privateKey, publicKey))
                                            .addLast(new ChannelInboundHandlerAdapter(){

                                                @Override
                                                public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                                                    if (evt instanceof ConnectResult) {
                                                        device.reverse("tcp:1234", adbInitializer)
                                                                .addListener(f -> {
                                                                    if (f.cause() != null) {
                                                                        //反向转发失败
                                                                        System.out.println("reverse failed:" + f.cause().getMessage());
                                                                    } else {
                                                                        //反向转发成功
                                                                        System.out.println("reverse success");
                                                                    }
                                                                });
                                                    }
                                                    ctx.fireUserEventTriggered(evt);
                                                }

                                                private void connect(ChannelHandlerContext ctx) {
                                                    device.connect()
                                                            .addListener(f -> {
                                                                if (f.cause() != null) {
                                                                    //连接失败，重连
                                                                    System.out.println("connect failed:" + f.cause().getMessage());
                                                                    connect(ctx);
                                                                } else {
                                                                    System.out.println("connect success");
                                                                }
                                                            });
                                                }

                                                @Override
                                                public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                                    ctx.fireChannelInactive();
                                                    device.close().addListener(f -> {
                                                        connect(ctx);
                                                    });
                                                }
                                            });
                                }
                            })
                            .connect(host, port);
                }
            };
            return new CloudAdbDevice(serial, privateKey, publicKey, factory);
        }

    }

    public static void main(String[] args) throws Exception {
        AbstractAdbDevice device = SocketAdbDevice.connect("192.168.137.122", 5555);
        device.connect();
        String result = device.shell("ls", "-l", "/").get();
    }
}
