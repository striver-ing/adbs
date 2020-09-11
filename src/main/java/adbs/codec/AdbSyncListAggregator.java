package adbs.codec;

import adbs.constant.SyncID;
import adbs.entity.SyncDent;
import adbs.entity.SyncMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.util.ArrayList;
import java.util.List;

public class AdbSyncListAggregator extends ChannelInboundHandlerAdapter {

    private final List<SyncDent> dents;

    public AdbSyncListAggregator() {
        this.dents = new ArrayList<>();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof SyncMessage)) {
            ctx.fireChannelRead(msg);
            return;
        }
        SyncMessage syncMessage = (SyncMessage) msg;
        if (syncMessage instanceof SyncDent) {
            dents.add((SyncDent) syncMessage);
        } else if (SyncID.DONE.equals(syncMessage.sid)) {
            SyncDent[] dentArray = new SyncDent[dents.size()];
            dents.toArray(dentArray);
            dents.clear();
            ctx.fireChannelRead(dentArray);
        } else {
            ctx.fireChannelRead(msg);
        }
    }
}
