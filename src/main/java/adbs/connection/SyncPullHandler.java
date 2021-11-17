package adbs.connection;

import adbs.constant.SyncID;
import adbs.device.AdbDevice;
import adbs.entity.sync.SyncData;
import adbs.entity.sync.SyncDataDone;
import adbs.entity.sync.SyncFail;
import adbs.entity.sync.SyncPath;
import adbs.exception.RemoteException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Promise;

import java.io.OutputStream;
import java.net.ProtocolException;
import java.nio.channels.ClosedChannelException;

public class SyncPullHandler extends ChannelInboundHandlerAdapter {

    private final AdbDevice device;

    private final String src;

    private final OutputStream dest;

    private final Promise promise;

    public SyncPullHandler(AdbDevice device, String src, OutputStream dest, Promise promise) {
        this.device = device;
        this.src = src;
        this.dest = dest;
        this.promise = promise;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ctx.writeAndFlush(new SyncPath(SyncID.RECV_V1, src))
                .addListener(f -> {
                    if (f.cause() != null) {
                        promise.tryFailure(f.cause());
                    }
                });
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof SyncFail) {
            promise.tryFailure(new RemoteException(((SyncFail) msg).error));
        } else if (msg instanceof SyncData) {
            ByteBuf buf = ((SyncData) msg).data;
            try {
                int size = buf.readableBytes();
                if (size > 0) {
                    buf.readBytes(dest, size);
                }
            } catch (Throwable cause) {
                promise.tryFailure(cause);
            } finally {
                ReferenceCountUtil.safeRelease(msg);
            }
        } else if (msg instanceof SyncDataDone) {
            promise.trySuccess(null);
        } else {
            promise.tryFailure(new ProtocolException("Error reply:" + msg));
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        promise.tryFailure(cause);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        promise.tryFailure(new ClosedChannelException());
    }

}
