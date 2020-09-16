package adbs.entity.sync;

import adbs.entity.StatMode;

import java.util.Date;

public class SyncDent extends SyncStat {

    public final String name;

    public SyncDent(StatMode mode, long size, Date mtime, String name) {
        super(mode, size, mtime);
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
