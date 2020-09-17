package adbs.channel;

import adbs.constant.Command;
import adbs.constant.Constants;
import adbs.entity.AdbPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ConnectionPendingException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class AdbChannel extends AbstractChannel implements ChannelInboundHandler {

    private static final Logger logger = LoggerFactory.getLogger(AdbChannel.class);

    private final EventLoop eventLoop;

    private final ChannelConfig config;

    private final ChannelMetadata metadata;

    private volatile ChannelHandlerContext context;

    private volatile ChannelPromise connectPromise;

    private volatile ScheduledFuture connectTimeoutFuture;

    private volatile AdbChannelAddress localAddress;

    private volatile AdbChannelAddress remoteAddress;

    private volatile int localId;

    private volatile int remoteId;

    public AdbChannel(Channel parent, int localId, int remoteId) {
        super(parent);
        this.localId = localId;
        this.remoteId = remoteId;
        this.eventLoop = new AdbChannelEventLoop(parent.eventLoop());
        this.metadata = new ChannelMetadata(false);
        this.config = new DefaultChannelConfig(this);
        this.config.setAllocator(parent.config().getAllocator());
        this.config.setConnectTimeoutMillis(parent.config().getConnectTimeoutMillis());
        this.config.setAutoClose(parent.config().isAutoClose());
        this.config.setAutoRead(parent.config().isAutoRead());
    }

    public ChannelHandlerContext context() {
        return this.context;
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new AdbUnsafe();
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return loop instanceof AdbChannelEventLoop;
    }

    @Override
    protected SocketAddress localAddress0() {
        return localAddress;
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return remoteAddress;
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        AdbChannelAddress address = (AdbChannelAddress) localAddress;
        this.localAddress = address;
        parent().writeAndFlush(new AdbPacket(Command.A_OKAY, address.id(), remoteId));
        this.localId = address.id();
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, ChannelPromise promise) {
        return super.connect(remoteAddress, promise);
    }

    @Override
    protected void doDisconnect() throws Exception {
        doClose();
    }

    @Override
    protected void doClose() throws Exception {
        ChannelPromise promise = connectPromise;
        if (promise != null) {
            // Use tryFailure() instead of setFailure() to avoid the race against cancel().
            promise.tryFailure(new ClosedChannelException());
            connectPromise = null;
        }

        ScheduledFuture<?> future = connectTimeoutFuture;
        if (future != null) {
            future.cancel(false);
            connectTimeoutFuture = null;
        }
        if (isActive()) {
            parent().writeAndFlush(new AdbPacket(Command.A_CLSE, localId, remoteId));
        }
        localId = remoteId = 0;
        pipeline().fireChannelInactive();
        parent().pipeline().remove(this);
    }

    @Override
    protected void doBeginRead() throws Exception {

    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        for (;;) {
            Object msg = in.current();
            if (msg == null) {
                // nothing left to write
                break;
            }
            if (msg instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) msg;
                int readableBytes = buf.readableBytes();
                while (readableBytes > 0) {
                    try {
                        parent().writeAndFlush(new AdbPacket(Command.A_WRTE, localId, remoteId, buf));
                    } catch (Throwable cause) {
                        ReferenceCountUtil.safeRelease(buf);
                    }
                    int newReadableBytes = buf.readableBytes();
                    in.progress(readableBytes - newReadableBytes);
                    readableBytes = newReadableBytes;
                }
                in.remove();
            } else {
                in.remove(new UnsupportedOperationException(
                        "unsupported message type: " + StringUtil.simpleClassName(msg)));
            }
        }
    }

    @Override
    public EventLoop eventLoop() {
        return eventLoop;
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public boolean isOpen() {
        return remoteId > 0 || localId > 0;
    }

    @Override
    public boolean isActive() {
        return localId > 0 && remoteId > 0;
    }

    @Override
    public ChannelMetadata metadata() {
        return metadata;
    }

    //ChannelInboundHandler
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        pipeline().fireChannelRegistered();
        ctx.fireChannelRegistered();
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        pipeline().fireChannelUnregistered();
        ctx.fireChannelUnregistered();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        pipeline().fireChannelActive();
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        pipeline().fireChannelInactive();
        ctx.fireChannelInactive();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof AdbPacket) {
            AdbPacket packet = (AdbPacket) msg;
            switch (packet.command) {
                case A_OKAY:
                    if (!isActive()) {
                        if (connectPromise == null) {
                            return;
                        }
                        boolean promiseSet = connectPromise.trySuccess();
                        if (!promiseSet) {
                            close(voidPromise());
                        } else {
                            this.remoteId = packet.arg0;
                            this.context = ctx;
                            this.eventLoop.register(this);
                        }
                    } else {
                        pipeline().fireUserEventTriggered("ACK");
                    }
                    break;

                case A_WRTE:
                    pipeline().fireChannelRead(packet.payload);
                    break;

                case A_CLSE:
                    //提前将remoteId置为0，这样isActive方法就返回false，避免重复发送
                    this.remoteId = 0;
                    close();
                    break;

            }
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        pipeline().fireChannelReadComplete();
        ctx.fireChannelReadComplete();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        pipeline().fireUserEventTriggered(evt);
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        pipeline().fireChannelWritabilityChanged();
        ctx.fireChannelWritabilityChanged();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        pipeline().fireExceptionCaught(cause);
        ctx.fireExceptionCaught(cause);
    }

    //ChannelHandler
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {

    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {

    }

    private class AdbUnsafe extends AbstractUnsafe {

        @Override
        public final void connect(
                final SocketAddress remoteAddress, final SocketAddress localAddress, final ChannelPromise promise) {
            AdbChannel.this.remoteAddress = (AdbChannelAddress) remoteAddress;
            ByteBuf buf = null;
            try {
                if (!promise.setUncancellable() || !ensureOpen(promise)) {
                    return;
                }
                if (connectPromise != null) {
                    throw new ConnectionPendingException();
                }

                connectPromise = promise;

                byte[] b = AdbChannel.this.remoteAddress.destination().getBytes(StandardCharsets.UTF_8);
                buf = alloc().buffer(b.length);
                buf.writeBytes(b);
                parent().writeAndFlush(new AdbPacket(Command.A_OPEN, localId, remoteId, buf))
                        .addListener(f -> {
                            if (f.cause() != null) {
                                connectPromise.setFailure(f.cause());
                            }
                        });

                int connectTimeoutMillis = config().getConnectTimeoutMillis();
                if (connectTimeoutMillis > 0) {
                    connectTimeoutFuture = eventLoop().schedule(() -> {
                        ChannelPromise connectPromise = AdbChannel.this.connectPromise;
                        ConnectTimeoutException cause =
                                new ConnectTimeoutException("connection timed out: " + remoteAddress);
                        if (connectPromise != null && connectPromise.tryFailure(cause)) {
                            close(voidPromise());
                        }
                    }, connectTimeoutMillis, TimeUnit.MILLISECONDS);
                }

                promise.addListener((ChannelFutureListener) future -> {
                    if (future.isCancelled()) {
                        if (connectTimeoutFuture != null) {
                            connectTimeoutFuture.cancel(false);
                        }
                        connectPromise = null;
                        close(voidPromise());
                    }
                });

            } catch (Throwable t) {
                if (buf != null) {
                    ReferenceCountUtil.safeRelease(buf);
                }
                promise.tryFailure(annotateConnectException(t, remoteAddress));
                closeIfClosed();
            }
        }

    }

}
