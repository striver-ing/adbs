package adbs.channel;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractTransport<I, O> extends ChannelInboundHandlerAdapter implements Transport<I, O> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractTransport.class);

    protected final AdbChannel channel;

    public AbstractTransport(AdbChannel channel) {
        this.channel = channel;
        this.init();
        this.channel.addHandler(this);
    }

    @Override
    public final void channelActive(ChannelHandlerContext ctx) throws Exception {
        onOpen();
    }

    @Override
    public final void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        I cast = (I) msg;
        onMessage(cast);
    }

    @Override
    public final void channelInactive(ChannelHandlerContext ctx) throws Exception {
        onClose();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        onError(cause);
    }

    /**
     * init 用户向channel pipeline中添加各种编解码
     */
    protected abstract void init();

    @Override
    public void onOpen() {
        //do nth...
    }

    @Override
    public void onClose() {
        //do nth...
    }

    @Override
    public void onError(Throwable cause) {
        //do nth...
    }

    @Override
    public Future<?> write(O request) {
        return channel.writeAndFlush(request);
    }

    @Override
    public Future<?> close() {
        return channel.close();
    }
}
