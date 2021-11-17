package adbs.connection;

import adbs.constant.SyncID;
import adbs.device.AdbDevice;
import adbs.entity.sync.*;
import adbs.exception.RemoteException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Promise;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProtocolException;
import java.nio.channels.ClosedChannelException;

import static adbs.constant.Constants.SYNC_DATA_MAX;

public class SyncPushHandler extends ChannelInboundHandlerAdapter {

    private final AdbDevice device;

    private final InputStream src;

    private final String dest;

    private final int mode;

    private final int mtime;

    private final Promise promise;

    public SyncPushHandler(AdbDevice device, InputStream src, String dest, int mode, int mtime, Promise promise) {
        this.device = device;
        this.src = src;
        this.dest = dest;
        this.mode = mode;
        this.mtime = mtime;
        this.promise = promise;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        //发送SEND指令
        String destAndMode = dest + "," + mode;
        ctx.writeAndFlush(new SyncPath(SyncID.SEND_V1, destAndMode))
                .addListener(f1 -> {
                    if (f1.cause() != null) {
                        promise.tryFailure(f1.cause());
                    }
                });
        //发送数据
        //启动一个新的线程读取流并发送数据, 不能阻塞当前线程，否则收不到响应
        new Thread() {
            @Override
            public void run() {
                try {
                    while (true) {
                        byte[] buffer = new byte[SYNC_DATA_MAX];
                        int size = src.read(buffer);
                        if (size == -1) {
                            break;
                        }
                        if (size == 0) {
                            continue;
                        }
                        ByteBuf payload = Unpooled.wrappedBuffer(buffer, 0, size);
                        try {
                            ctx.writeAndFlush(new SyncData(payload))
                                    .addListener(f2 -> {
                                        if (f2.cause() != null) {
                                            promise.tryFailure(f2.cause());
                                        }
                                    });
                        } catch (Throwable cause) {
                            ReferenceCountUtil.safeRelease(payload);
                            throw cause;
                        }
                    }
                    //发送done
                    ctx.writeAndFlush(new SyncDataDone(mtime))
                            .addListener(f3 -> {
                                if (f3.cause() != null) {
                                    promise.tryFailure(f3.cause());
                                }
                            });
                } catch (Throwable cause) {
                    promise.tryFailure(cause);
                }
            }
        }.start();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof SyncFail) {
            promise.tryFailure(new RemoteException(((SyncFail) msg).error));
        } else if (msg instanceof SyncOkay) {
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
