package adbs.entity.sync;

import adbs.constant.SyncID;

public class SyncPath {

    public final SyncID sid;

    public final String path;

    public SyncPath(SyncID sid, String path) {
        this.sid = sid;
        this.path = path;
    }
}
