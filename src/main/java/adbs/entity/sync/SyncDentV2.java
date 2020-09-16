package adbs.entity.sync;

import adbs.entity.StatMode;

import java.util.Date;

public class SyncDentV2 extends SyncDent {

    public final long error;

    public final long dev;

    public final long ino;

    public final long nlink;

    public final long uid;

    public final long gid;

    public final Date atime;

    public final Date ctime;

    public SyncDentV2(StatMode mode, long size,
                      Date mtime, String name, long error,
                      long dev, long ino, long nlink,
                      long uid, long gid, Date atime, Date ctime) {
        super(mode, size, mtime, name);
        this.error = error;
        this.dev = dev;
        this.ino = ino;
        this.nlink = nlink;
        this.uid = uid;
        this.gid = gid;
        this.atime = atime;
        this.ctime = ctime;
    }

    @Override
    public String toString() {
        return name;
    }
}
