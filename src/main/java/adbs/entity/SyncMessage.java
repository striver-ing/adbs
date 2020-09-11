package adbs.entity;

import adbs.constant.SyncID;

public class SyncMessage {

    public final SyncID sid;

    public SyncMessage(SyncID sid) {
        this.sid = sid;
    }

    @Override
    public String toString() {
        return "SyncMessage{" +
                "sid=" + sid +
                '}';
    }
}
