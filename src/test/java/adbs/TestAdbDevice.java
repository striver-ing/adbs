package adbs;

import adbs.device.DefaultAdbDevice;
import adbs.device.SocketAdbDevice;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class TestAdbDevice {

    private static final Logger logger = LoggerFactory.getLogger(TestAdbDevice.class);

    public static void main(String[] args) throws Exception {
        DefaultAdbDevice device = SocketAdbDevice.connect("127.0.0.1", 6000);
        ChannelFuture cf = device.open("shell:ls -l /sdcard/\0", 3, TimeUnit.SECONDS, channel -> {
            channel.pipeline()
                    .addLast(new LineBasedFrameDecoder(4096))
                    .addLast(new StringDecoder(StandardCharsets.UTF_8))
                    .addLast(new ChannelInboundHandlerAdapter(){
                @Override
                public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
                    System.out.println("channelRegistered");
                }

                @Override
                public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
                    System.out.println("channelUnregistered");
                }

                @Override
                public void channelActive(ChannelHandlerContext ctx) throws Exception {
                    System.out.println("channelActive");
                }

                @Override
                public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                    System.out.println("channelInactive");
                }

                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    System.out.println("channelRead");
                    System.out.println(msg);
                }

                @Override
                public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
                    System.out.println("channelReadComplete");
                }
            });
        });
        //device.reverse("tcp:1234", "tcp:www.baidu.com:80");
    }
}
