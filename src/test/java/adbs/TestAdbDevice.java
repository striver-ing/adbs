package adbs;

import adbs.constant.Constants;
import adbs.device.AdbDevice;
import adbs.device.DefaultAdbDevice;
import adbs.device.SocketAdbDevice;
import adbs.feature.AdbHttp;
import adbs.feature.impl.AdbHttpImpl;
import com.google.common.util.concurrent.SettableFuture;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.string.StringDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class TestAdbDevice {

    private static final Logger logger = LoggerFactory.getLogger(TestAdbDevice.class);

    public static void main(String[] args) throws Exception {
        InputStream is = new FileInputStream("D:\\tmp\\test.iso");
        DefaultAdbDevice device = SocketAdbDevice.connect("127.0.0.1", 6000);
        Long now = System.currentTimeMillis() / 1000;
        device.push(is, "/sdcard/xx.exe", AdbDevice.DEFAULT_MODE, now.intValue());
//        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
//        ChannelFuture cf = device.open(
//                "tcp:127.0.0.1:5555\0", Constants.DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS,
//                channel -> {
//                    channel.pipeline()
//                            .addLast(new HttpClientCodec())
//                            .addLast(new HttpObjectAggregator(8 * 1024 * 1024))
//                            .addLast(new ChannelInboundHandlerAdapter(){
//
////                                @Override
////                                public void channelActive(ChannelHandlerContext ctx) throws Exception {
////                                    ctx.writeAndFlush(request);
////                                }
//
//                                @Override
//                                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
//                                    if (msg instanceof FullHttpResponse) {
//                                        System.out.println(msg);
//                                        ctx.close();
//                                    } else {
//                                        ctx.fireChannelRead(msg);
//                                    }
//                                }
//
//                                @Override
//                                public void channelInactive(ChannelHandlerContext ctx) throws Exception {
//                                    //future.setException(new ClosedChannelException());
//                                }
//                            });
//                });
//        cf.channel().writeAndFlush("test");
    }
}
