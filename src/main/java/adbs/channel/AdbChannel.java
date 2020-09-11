package adbs.channel;

import adbs.connection.AdbChannelProcessor;
import adbs.constant.Command;
import adbs.entity.AdbPacket;
import io.netty.channel.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class AdbChannel {

    private final Channel channel;

    private final int localId;

    private final int remoteId;

    private final AtomicInteger counter;

    private final String firstHandlerName;

    private final ChannelPromise closePromise;

    public AdbChannel(Channel channel, int localId, int remoteId) {
        this.channel = channel;
        this.localId = localId;
        this.remoteId = remoteId;
        this.counter = new AtomicInteger();
        this.firstHandlerName = AdbChannelProcessor.firstHandlerName(localId);
        this.closePromise = channel.newPromise();
    }

    public int localId() {
        return localId;
    }

    public int remoteId() {
        return remoteId;
    }

    private ChannelHandlerContext lastContext() {
        String lastHandlerName = AdbChannelProcessor.lastHandlerName(localId);
        return channel.pipeline().context(lastHandlerName);
    }

    private String buildName(int id) {
        StringBuilder sb = new StringBuilder();
        sb.append("ADB-CHANNEL#");
        sb.append(localId);
        sb.append("#");
        sb.append(id);
        return sb.toString();
    }

    public AdbChannel addHandler(ChannelHandler handler) {
        int id = counter.getAndIncrement();
        String name = buildName(id);
        if (id == 0) {
            channel.pipeline().addAfter(firstHandlerName, name, handler);
        } else {
            String prev = buildName(id - 1);
            channel.pipeline().addAfter(prev, name, handler);
        }
        return this;
    }

    public ChannelFuture writeAndFlush(Object obj, ChannelPromise promise) {
        return lastContext().writeAndFlush(obj, promise);
    }

    public ChannelFuture writeAndFlush(Object obj) {
        return lastContext().writeAndFlush(obj, channel.newPromise());
    }

    public ChannelFuture close() {
        ChannelHandlerContext context = lastContext();
        if (context != null) {
            context.writeAndFlush(new AdbPacket(Command.A_CLSE, localId, remoteId, null), closePromise);
        } else {
            closePromise.trySuccess();
        }
        return closePromise;
    }
}
