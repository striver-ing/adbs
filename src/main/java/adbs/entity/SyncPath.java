package adbs.entity;

import adbs.constant.SyncID;

public class SyncPath extends SyncMessage {

    public final String path;

    public SyncPath(SyncID sid, String path) {
        super(sid);
        this.path = path;
    }
}
