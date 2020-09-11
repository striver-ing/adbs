package adbs.entity;

import adbs.constant.SyncID;

import java.util.Date;

public class SyncStat extends SyncMessage {

    public final StatMode mode;

    public final long size;

    public final Date mtime;

    public SyncStat(SyncID sid, StatMode mode, long size, Date mtime) {
        super(sid);
        this.mode = mode;
        this.size = size;
        this.mtime = mtime;
    }
}
