package adbs.entity;

import io.netty.channel.ChannelPromise;

public class PendingWriteEntry {

    public final Object msg;

    public final ChannelPromise promise;

    public PendingWriteEntry(Object msg, ChannelPromise promise) {
        this.msg = msg;
        this.promise = promise;
    }
}
