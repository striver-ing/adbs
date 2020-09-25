package adbs.feature.impl;

import adbs.constant.Constants;
import adbs.device.AdbDevice;
import adbs.feature.AdbHttp;
import com.google.common.util.concurrent.SettableFuture;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;

import java.io.IOException;
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
    public FullHttpResponse execute(FullHttpRequest request, long timeout, TimeUnit unit) throws IOException {
        SettableFuture<FullHttpResponse> future = SettableFuture.create();
        ChannelFuture cf = device.open(
                "tcp:" + port + "\0", Constants.DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS,
                channel -> {
                    channel.pipeline()
                            .addLast(new HttpClientCodec())
                            .addLast(new HttpObjectAggregator(8 * 1024 * 1024))
                            .addLast(new ChannelInboundHandlerAdapter(){

                                @Override
                                public void channelActive(ChannelHandlerContext ctx) throws Exception {
                                    ctx.writeAndFlush(request).addListener(f -> {
                                        if (f.cause() != null) {
                                            future.setException(f.cause());
                                        }
                                    });
                                }

                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                    if (msg instanceof FullHttpResponse) {
                                        future.set((FullHttpResponse) msg);
                                    } else {
                                        ctx.fireChannelRead(msg);
                                    }
                                }

                                @Override
                                public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                    future.setException(new ClosedChannelException());
                                }
                            });
        });
        cf.addListener(f -> {
            if (f.cause() != null) {
                future.setException(f.cause());
            }
        });
        try {
            return future.get(timeout, unit);
        } catch (Throwable cause) {
            throw new IOException(cause.getMessage(), cause);
        } finally {
            cf.channel().close();
        }
    }
}
