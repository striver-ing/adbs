package adbs.codec;

import adbs.entity.sync.SyncDent;
import adbs.entity.sync.SyncDentDone;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.util.ArrayList;
import java.util.List;

public class SyncDentAggregator extends ChannelInboundHandlerAdapter {

    private final List<SyncDent> dents;

    public SyncDentAggregator() {
        this.dents = new ArrayList<>();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof SyncDent) {
            dents.add((SyncDent) msg);
        } else if (msg instanceof SyncDentDone) {
            SyncDent[] dentArray = new SyncDent[dents.size()];
            dents.toArray(dentArray);
            dents.clear();
            ctx.fireChannelRead(dentArray);
        } else {
            ctx.fireChannelRead(msg);
        }
    }
}
