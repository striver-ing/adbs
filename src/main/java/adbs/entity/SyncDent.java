package adbs.entity;

import adbs.constant.SyncID;

import java.util.Date;

public class SyncDent extends SyncStat {

    public final String name;

    public SyncDent(SyncID sid, StatMode mode, long size, Date mtime, String name) {
        super(sid, mode, size, mtime);
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
