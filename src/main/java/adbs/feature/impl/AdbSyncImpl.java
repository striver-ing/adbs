package adbs.feature.impl;

import adbs.codec.*;
import adbs.constant.Feature;
import adbs.constant.FileSystemErrorCode;
import adbs.constant.SyncID;
import adbs.device.AdbDevice;
import adbs.entity.sync.*;
import adbs.exception.RemoteException;
import adbs.feature.AdbSync;
import com.google.common.util.concurrent.SettableFuture;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static adbs.constant.Constants.DEFAULT_READ_TIMEOUT;
import static adbs.constant.Constants.SYNC_DATA_MAX;

public class AdbSyncImpl implements AdbSync {

    private final AdbDevice device;

    public AdbSyncImpl(AdbDevice device) {
        this.device = device;
    }

    private <T> T sync(SettableFuture<T> future, ChannelHandler... handlers) throws IOException {
        ChannelFuture cf = device.open("sync:\0", channel -> {
            channel.pipeline().addLast(handlers);
        });
        try {
            return future.get(DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
        } catch (ExecutionException e0) {
            if (e0.getCause() instanceof IOException) {
                throw (IOException) e0.getCause();
            }
            throw new IOException(e0.getCause().getMessage(), e0.getCause());
        } catch (Throwable e1) {
            throw new IOException(e1.getMessage(), e1);
        } finally {
            cf.channel().close();
        }
    }

    public SyncStat stat(String path) throws IOException {
        boolean hasStatV2 = device.features().contains(Feature.STAT_V2);
        SyncID sid = hasStatV2 ? SyncID.STAT_V2 : SyncID.LSTAT_V1;
        SyncPath syncPath = new SyncPath(sid, path);
        SettableFuture<SyncStat> future = SettableFuture.create();
        SyncStat stat = sync(
                future,
                new SyncStatDecoder(),
                new SyncEncoder(),
                new ChannelInboundHandlerAdapter(){
                    @Override
                    public void channelActive(ChannelHandlerContext ctx) throws Exception {
                        ctx.writeAndFlush(syncPath).addListener(f -> {
                            if (f.cause() != null) {
                                future.setException(f.cause());
                            }
                        });
                    }

                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                        if (msg instanceof SyncFail) {
                            future.setException(new RemoteException(((SyncFail) msg).error));
                        } else if (msg instanceof SyncStat) {
                            future.set((SyncStat) msg);
                        } else {
                            future.setException(new ProtocolException("Error reply:" + msg));
                        }
                    }
                });
        if (stat instanceof SyncStatV2 && ((SyncStatV2) stat).error > 0) {
            Long err = ((SyncStatV2) stat).error;
            throw new RemoteException("FileSystem Error:" + FileSystemErrorCode.findByValue(err.intValue()));
        } else if (stat instanceof SyncStat && stat.mode.type == null) {
            throw new RemoteException("FileSystem Error");
        }
        return stat;
    }

    public SyncDent[] list(String path) throws IOException {
        boolean hasLsV2 = device.features().contains(Feature.LS_V2);
        SyncID sid = hasLsV2 ? SyncID.LIST_V2 : SyncID.LIST_V1;
        SyncPath syncPath = new SyncPath(sid, path);
        SettableFuture<SyncDent[]> future = SettableFuture.create();
        return sync(
                future,
                new SyncDentDecoder(),
                new SyncDentAggregator(),
                new SyncEncoder(),
                new ChannelInboundHandlerAdapter(){
                    @Override
                    public void channelActive(ChannelHandlerContext ctx) throws Exception {
                        ctx.writeAndFlush(syncPath).addListener(f -> {
                            if (f.cause() != null) {
                                future.setException(f.cause());
                            }
                        });
                    }

                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                        if (msg instanceof SyncFail) {
                            future.setException(new RemoteException(((SyncFail) msg).error));
                        } else if (msg instanceof SyncDent[]) {
                            future.set((SyncDent[]) msg);
                        } else {
                            future.setException(new ProtocolException("Error reply:" + msg));
                        }
                    }
                });
    }

    public int pull(String src, OutputStream dest) throws IOException {
        SettableFuture<Integer> future = SettableFuture.create();
        AtomicInteger counter = new AtomicInteger(0);
        return sync(
                future,
                new SyncDataDecoder(),
                new SyncEncoder(),
                new ChannelInboundHandlerAdapter(){
                    @Override
                    public void channelActive(ChannelHandlerContext ctx) throws Exception {
                        ctx.writeAndFlush(new SyncPath(SyncID.RECV_V1, src)).addListener(f -> {
                            if (f.cause() != null) {
                                future.setException(f.cause());
                            }
                        });
                    }

                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                        if (msg instanceof SyncFail) {
                            future.setException(new RemoteException(((SyncFail) msg).error));
                        } else if (msg instanceof SyncData) {
                            ByteBuf buf = ((SyncData) msg).data;
                            try {
                                int size = buf.readableBytes();
                                if (size > 0) {
                                    buf.readBytes(dest, size);
                                    counter.addAndGet(size);
                                }
                            } catch (Throwable cause) {
                                future.setException(cause);
                            } finally {
                                ReferenceCountUtil.safeRelease(msg);
                            }
                        } else if (msg instanceof SyncDataDone) {
                            future.set(counter.get());
                            ctx.writeAndFlush(new SyncQuit());
                        } else {
                            future.setException(new ProtocolException("Error reply:" + msg));
                        }
                    }
                });
    }

    public int push(InputStream src, String dest, int mode, int mtime) throws IOException {
        List<Object> list = new ArrayList<>();
        String destAndMode = dest + "," + mode;
        list.add(new SyncPath(SyncID.SEND_V1, destAndMode));
        AtomicInteger counter = new AtomicInteger();
        while (true) {
            byte[] data = new byte[SYNC_DATA_MAX];
            ByteBuf payload = null;
            try {
                int size = src.read(data);
                if (size == -1) {
                    break;
                }
                if (size == 0) {
                    continue;
                }
                payload = Unpooled.wrappedBuffer(data, 0, size);
                list.add(new SyncData(payload));
                counter.addAndGet(size);
            } catch (Throwable cause) {
                if (payload != null) {
                    ReferenceCountUtil.safeRelease(payload);
                }
                if (cause instanceof IOException) {
                    throw (IOException) cause;
                } else {
                    throw new IOException(cause.getMessage(), cause);
                }
            }
        }
        list.add(new SyncDataDone(mtime));
        SettableFuture<Integer> future = SettableFuture.create();
        return sync(
                future,
                new SyncDecoder(),
                new SyncEncoder(),
                new ChannelInboundHandlerAdapter(){
                    @Override
                    public void channelActive(ChannelHandlerContext ctx) throws Exception {
                        for(Object message : list) {
                            ctx.writeAndFlush(message).addListener(f -> {
                                if (f.cause() != null) {
                                    future.setException(f.cause());
                                }
                            });
                        }
                    }

                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                        if (msg instanceof SyncFail) {
                            future.setException(new RemoteException(((SyncFail) msg).error));
                        } else if (msg instanceof SyncOkay) {
                            future.set(counter.get());
                            ctx.writeAndFlush(new SyncQuit());
                        } else {
                            future.setException(new ProtocolException("Error reply:" + msg));
                        }
                    }
                });
    }
}
