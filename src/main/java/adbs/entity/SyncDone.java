package adbs.entity;

import adbs.constant.SyncID;

public class SyncDone extends SyncMessage {

    public final int mtime;

    public SyncDone(SyncID sid, int mtime) {
        super(sid);
        this.mtime = mtime;
    }

    public SyncDone(int mtime) {
        this(SyncID.DONE, mtime);
    }
}
