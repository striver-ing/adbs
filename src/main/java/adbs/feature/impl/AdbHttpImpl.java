package adbs.feature.impl;

import adbs.device.AdbDevice;
import adbs.feature.AdbHttp;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

import java.nio.channels.ClosedChannelException;
import java.util.concurrent.TimeUnit;

public class AdbHttpImpl implements AdbHttp {

    private final AdbDevice device;

    private final int port;

    public AdbHttpImpl(AdbDevice device, int port) {
        this.device = device;
        this.port = port;
    }

    @Override
    public FullHttpResponse execute(HttpRequest request, long timeout, TimeUnit unit) throws Exception {
        Promise<FullHttpResponse> promise = new DefaultPromise<>(device.executor());
        Future<Channel> future = device.open(
                "tcp:" + port + "\0", timeout, unit,
                channel -> {
                    channel.pipeline()
                            .addLast(new HttpClientCodec())
                            .addLast(new HttpObjectAggregator(8 * 1024 * 1024))
                            .addLast(new ChannelInboundHandlerAdapter(){

                                @Override
                                public void channelActive(ChannelHandlerContext ctx) throws Exception {
                                    ctx.writeAndFlush(request).addListener(f -> {
                                        if (f.cause() != null) {
                                            promise.tryFailure(f.cause());
                                        }
                                    });
                                }

                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                    if (msg instanceof FullHttpResponse) {
                                        promise.trySuccess((FullHttpResponse) msg);
                                    } else {
                                        ctx.fireChannelRead(msg);
                                    }
                                }

                                @Override
                                public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                    promise.tryFailure(new ClosedChannelException());
                                }
                            });
        });
        Channel channel = future.get(timeout, unit);
        try {
            return promise.get(timeout, unit);
        } finally {
            channel.close();
        }
    }
}
