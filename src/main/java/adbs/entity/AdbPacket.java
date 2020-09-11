package adbs.entity;

import adbs.constant.Command;
import adbs.util.MessageUtil;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCounted;

public class AdbPacket implements ReferenceCounted {

    public final Command command;

    public final int arg0;

    public final int arg1;

    public final int size;

    public final int checksum;

    public final int magic;

    public final ByteBuf payload;

    public AdbPacket(Command command, int arg0, int arg1, int size, int checksum, int magic, ByteBuf payload) {
        this.command = command;
        this.arg0 = arg0;
        this.arg1 = arg1;
        this.size = size;
        this.checksum = checksum;
        this.magic = magic;
        this.payload = payload;
    }

    public AdbPacket(Command command, int arg0, int arg1, ByteBuf payload) {
        this.command = command;
        this.arg0 = arg0;
        this.arg1 = arg1;
        this.size = payload != null ? payload.readableBytes() : 0;
        this.checksum = payload != null ? MessageUtil.checksum(payload) : 0;
        this.magic = command.magic();
        this.payload = payload;
    }

    public AdbPacket(Command command, int arg0, int arg1) {
        this(command, arg0, arg1, null);
    }


    @Override
    public int refCnt() {
        return this.payload != null ? this.payload.refCnt() : 0;
    }

    @Override
    public ReferenceCounted retain() {
        if (this.payload != null) {
            this.payload.retain();
        }
        return this;
    }

    @Override
    public ReferenceCounted retain(int increment) {
        if (this.payload != null) {
            this.payload.retain(increment);
        }
        return this;
    }

    @Override
    public ReferenceCounted touch() {
        if (this.payload != null) {
            this.payload.touch();
        }
        return this;
    }

    @Override
    public ReferenceCounted touch(Object hint) {
        if (this.payload != null) {
            this.payload.touch(hint);
        }
        return this;
    }

    @Override
    public boolean release() {
        return this.payload != null ? this.payload.release() : true;
    }

    @Override
    public boolean release(int decrement) {
        return this.payload != null ? this.payload.release(decrement) : true;
    }

    @Override
    public String toString() {
        return "AdbPacket{" +
                "command=" + command +
                ", arg0=" + arg0 +
                ", arg1=" + arg1 +
                ", size=" + size +
                ", checksum=" + checksum +
                ", magic=" + magic +
                ", payload=" + payload +
                '}';
    }
}
