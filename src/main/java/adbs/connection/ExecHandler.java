package adbs.connection;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.Promise;

public class ExecHandler extends ChannelInboundHandlerAdapter {

    private final Promise<String> promise;

    private final StringBuilder sb;

    public ExecHandler(Promise<String> promise) {
        this.promise = promise;
        this.sb = new StringBuilder();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof CharSequence) {
            sb.append(msg);
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        try {
            promise.trySuccess(sb.toString());
        } catch (Throwable cause) {
            promise.tryFailure(cause);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        promise.tryFailure(cause);
    }

}
