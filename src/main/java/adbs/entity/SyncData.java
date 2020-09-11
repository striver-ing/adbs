package adbs.entity;

import adbs.constant.SyncID;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCounted;

public class SyncData extends SyncMessage implements ReferenceCounted {

    public final ByteBuf data;

    public SyncData(SyncID sid, ByteBuf data) {
        super(sid);
        this.data = data;
    }

    public SyncData(ByteBuf data) {
        this(SyncID.DATA, data);
    }

    @Override
    public int refCnt() {
        return data.refCnt();
    }

    @Override
    public ReferenceCounted retain() {
        data.retain();
        return this;
    }

    @Override
    public ReferenceCounted retain(int increment) {
        data.retain(increment);
        return this;
    }

    @Override
    public ReferenceCounted touch() {
        data.touch();
        return this;
    }

    @Override
    public ReferenceCounted touch(Object hint) {
        data.touch(hint);
        return this;
    }

    @Override
    public boolean release() {
        return data.release();
    }

    @Override
    public boolean release(int decrement) {
        return data.release(decrement);
    }
}
