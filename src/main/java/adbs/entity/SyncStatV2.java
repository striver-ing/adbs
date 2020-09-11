package adbs.entity;

import adbs.constant.SyncID;

import java.util.Date;

public class SyncStatV2 extends SyncStat {

    public final long error;

    public final long dev;

    public final long ino;

    public final long nlink;

    public final long uid;

    public final long gid;

    public final Date atime;

    public final Date ctime;

    public SyncStatV2(SyncID sid, StatMode mode, long size,
                      Date mtime, long error, long dev,
                      long ino, long nlink, long uid,
                      long gid, Date atime, Date ctime) {
        super(sid, mode, size, mtime);
        this.error = error;
        this.dev = dev;
        this.ino = ino;
        this.nlink = nlink;
        this.uid = uid;
        this.gid = gid;
        this.atime = atime;
        this.ctime = ctime;
    }
}
