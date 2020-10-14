package adbs.codec;

import adbs.constant.FileType;
import adbs.constant.SyncID;
import adbs.entity.FilePerm;
import adbs.entity.StatMode;
import adbs.entity.sync.SyncStat;
import adbs.entity.sync.SyncStatV2;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import java.net.ProtocolException;
import java.util.Date;
import java.util.List;

import static adbs.constant.Constants.*;

public class SyncStatDecoder extends SyncDecoder {

    private static StatMode toMode(int mode) {
        FileType type = FileType.findByValue(mode);
        FilePerm usrPerm = new FilePerm(
                (mode & S_IRUSR) == S_IRUSR,
                (mode & S_IWUSR) == S_IWUSR,
                (mode & S_IXUSR) == S_IXUSR
        );
        FilePerm grpPerm = new FilePerm(
                (mode & S_IRGRP) == S_IRGRP,
                (mode & S_IWGRP) == S_IWGRP,
                (mode & S_IXGRP) == S_IXGRP
        );
        FilePerm othPerm = new FilePerm(
                (mode & S_IROTH) == S_IROTH,
                (mode & S_IWOTH) == S_IWOTH,
                (mode & S_IXOTH) == S_IXOTH
        );
        return new StatMode(type, usrPerm, grpPerm, othPerm);
    }

    protected static SyncStat decodeStat(ByteBuf in) {
        int mode = in.readIntLE();
        long size = in.readUnsignedIntLE();
        long mtime = in.readUnsignedIntLE();
        return new SyncStat(
                mode != 0 ? toMode(mode) : null,
                size,
                mtime != 0 ? new Date(mtime * 1000) : null);
    }

    protected static SyncStatV2 decodeStatV2(ByteBuf in) {
        int error = in.readIntLE();
        long dev = in.readLongLE();
        long ino = in.readLongLE();
        int mode = in.readIntLE();
        long nlink = in.readUnsignedIntLE();
        long uid = in.readUnsignedIntLE();
        long gid = in.readUnsignedIntLE();
        long size = in.readLongLE();
        long atime = in.readLongLE();
        long mtime = in.readLongLE();
        long ctime = in.readLongLE();
        return new SyncStatV2(
                error == 0 ? toMode(mode) : null,
                size,
                error == 0 ? new Date(mtime * 1000) : null,
                error,
                dev,
                ino,
                nlink,
                uid,
                gid,
                error == 0 ? new Date(atime * 1000) : null,
                error == 0 ? new Date(ctime * 1000) : null);
    }

    private void decodeStat(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() >= 16) {
            in.skipBytes(4);
            out.add(decodeStat(in));
        }
    }

    private void decodeStatV2(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() >= 72) {
            in.skipBytes(4);
            out.add(decodeStatV2(in));
        }
    }

    @Override
    protected void decode(SyncID sid, ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        switch (sid) {
            case LSTAT_V1:
                decodeStat(ctx, in, out);
                break;

            case STAT_V2:
                decodeStatV2(ctx, in, out);
                break;

            default:
                ctx.fireExceptionCaught(new ProtocolException("unsupported sid:" + sid));
                break;
        }
    }
}
