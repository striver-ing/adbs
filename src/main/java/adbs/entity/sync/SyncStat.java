package adbs.entity.sync;

import adbs.entity.StatMode;

import java.util.Date;

public class SyncStat {

    public final StatMode mode;

    public final long size;

    public final Date mtime;

    public SyncStat(StatMode mode, long size, Date mtime) {
        this.mode = mode;
        this.size = size;
        this.mtime = mtime;
    }
}
