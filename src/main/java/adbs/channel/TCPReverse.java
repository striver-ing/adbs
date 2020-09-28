package adbs.channel;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

public class TCPReverse implements AdbChannelInitializer {

    private final String host;

    private final int port;

    private final EventLoopGroup eventLoop;

    public TCPReverse(String host, int port, EventLoopGroup eventLoop) {
        this.host = host;
        this.port = port;
        this.eventLoop = eventLoop;
    }

    @Override
    public void initChannel(Channel channel) {
        Bootstrap bootstrap = new Bootstrap();
        Channel chl = bootstrap.group(eventLoop)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_LINGER, 3)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.AUTO_CLOSE, false)
                .handler(new ChannelInitializer<SocketChannel>() {

                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter(){
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                channel.writeAndFlush(msg);
                            }

                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                channel.close();
                            }
                        });
                    }
                })
                .connect(host, port)
                .addListener(f -> {
                    //TODO 此处需要处理连接不上的情况，直接close会给应用造成困扰，需要改造
                    if (f.cause() != null) {
                        channel.close();
                    }
                })
                .channel();

        channel.pipeline().addLast(new ChannelInboundHandlerAdapter(){
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                chl.writeAndFlush(msg);
            }

            @Override
            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                chl.close();
            }
        });
    }
}
