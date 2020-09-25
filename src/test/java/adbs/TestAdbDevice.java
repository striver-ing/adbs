package adbs;

import adbs.constant.Constants;
import adbs.device.DefaultAdbDevice;
import adbs.device.SocketAdbDevice;
import adbs.feature.AdbHttp;
import adbs.feature.impl.AdbHttpImpl;
import com.google.common.util.concurrent.SettableFuture;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.string.StringDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class TestAdbDevice {

    private static final Logger logger = LoggerFactory.getLogger(TestAdbDevice.class);

    public static void main(String[] args) throws Exception {
        DefaultAdbDevice device = SocketAdbDevice.connect("127.0.0.1", 6000);
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
        ChannelFuture cf = device.open(
                "tcp:127.0.0.1:5555\0", Constants.DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS,
                channel -> {
                    channel.pipeline()
                            .addLast(new HttpClientCodec())
                            .addLast(new HttpObjectAggregator(8 * 1024 * 1024))
                            .addLast(new ChannelInboundHandlerAdapter(){

                                @Override
                                public void channelActive(ChannelHandlerContext ctx) throws Exception {
                                    ctx.writeAndFlush(request);
                                }

                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                    if (msg instanceof FullHttpResponse) {
                                        System.out.println(msg);
                                        ctx.close();
                                    } else {
                                        ctx.fireChannelRead(msg);
                                    }
                                }

                                @Override
                                public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                    //future.setException(new ClosedChannelException());
                                }
                            });
                });
    }
}
