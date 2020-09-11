package adbs.entity;

import adbs.constant.SyncID;

public class SyncFail extends SyncMessage {

    public final String error;

    public SyncFail(SyncID sid, String error) {
        super(sid);
        this.error = error;
    }

    public SyncFail(String error) {
        super(SyncID.FAIL);
        this.error = error;
    }
}
