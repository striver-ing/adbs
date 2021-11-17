package adbs.connection;

import adbs.constant.Feature;
import adbs.constant.SyncID;
import adbs.device.AdbDevice;
import adbs.entity.sync.SyncDent;
import adbs.entity.sync.SyncFail;
import adbs.entity.sync.SyncPath;
import adbs.entity.sync.SyncStat;
import adbs.exception.RemoteException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.Promise;

import java.net.ProtocolException;
import java.nio.channels.ClosedChannelException;

public class SyncListHandler extends ChannelInboundHandlerAdapter {

    private final AdbDevice device;

    private final String path;

    private final Promise<SyncDent[]> promise;

    public SyncListHandler(AdbDevice device, String path, Promise<SyncDent[]> promise) {
        this.device = device;
        this.path = path;
        this.promise = promise;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        boolean hasLsV2 = device.features().contains(Feature.LS_V2);
        SyncID sid = hasLsV2 ? SyncID.LIST_V2 : SyncID.LIST_V1;
        SyncPath syncPath = new SyncPath(sid, path);
        ctx.writeAndFlush(syncPath)
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
        } else if (msg instanceof SyncDent[]) {
            promise.trySuccess((SyncDent[]) msg);
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
