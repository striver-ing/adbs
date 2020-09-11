package adbs.codec;

import adbs.constant.FileType;
import adbs.constant.SyncID;
import adbs.entity.*;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static adbs.constant.Constants.*;

public class AdbSyncDecoder extends ByteToMessageDecoder {

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

    private static SyncStat _decodeStat(SyncID sid, ByteBuf in) {
        int mode = in.readIntLE();
        long size = in.readUnsignedIntLE();
        long mtime = in.readUnsignedIntLE();
        return new SyncStat(
                sid,
                toMode(mode),
                size,
                new Date(mtime * 1000));
    }

    private static SyncStatV2 _decodeStatV2(SyncID sid, ByteBuf in) {
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
                sid,
                error == 0 ? toMode(mode) : null,
                size,
                new Date(mtime * 1000),
                error,
                dev,
                ino,
                nlink,
                uid,
                gid,
                new Date(atime * 1000),
                new Date(ctime * 1000));
    }


    protected void decodeStat(SyncID sid, ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() >= 16) {
            in.skipBytes(4);
            out.add(_decodeStat(sid, in));
        }
    }

    protected void decodeStatV2(SyncID sid, ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() >= 72) {
            in.skipBytes(4);
            out.add(_decodeStatV2(sid, in));
        }
    }

    protected void decodeDent(SyncID sid, ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        int readerIndex = in.readerIndex();
        int len = in.getIntLE(readerIndex + 16);
        if (in.readableBytes() >= 20 + len) {
            in.skipBytes(4);
            SyncStat stat = _decodeStat(sid, in);
            in.skipBytes(4);
            byte[] b = new byte[len];
            in.readBytes(b);
            out.add(new SyncDent(
                    stat.sid,
                    stat.mode,
                    stat.size,
                    stat.mtime,
                    new String(b, StandardCharsets.UTF_8)
            ));
        }
    }

    protected void decodeDentV2(SyncID sid, ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        int readerIndex = in.readerIndex();
        int len = in.getIntLE(readerIndex + 72);
        if (in.readableBytes() >= 76 + len) {
            in.skipBytes(4);
            SyncStatV2 stat = _decodeStatV2(sid, in);
            in.skipBytes(4);
            byte[] b = new byte[len];
            in.readBytes(b);
            out.add(new SyncDentV2(stat.sid, stat.mode, stat.size, stat.mtime,
                    new String(b, StandardCharsets.UTF_8),
                    stat.error, stat.dev, stat.ino, stat.nlink,
                    stat.uid, stat.gid, stat.atime, stat.ctime));
        }
    }

    protected void decodeFail(SyncID sid, ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        int readerIndex = in.readerIndex();
        int len = in.getIntLE(readerIndex + 4);
        if (in.readableBytes() >= 8 + len) {
            byte[] b = new byte[len];
            in.skipBytes(8);
            in.readBytes(b);
            out.add(new SyncFail(sid, new String(b, StandardCharsets.UTF_8)));
        }
    }

    protected void decodeSid(SyncID sid, ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception{
        in.skipBytes(8);
        out.add(new SyncMessage(sid));
    }

    protected void decodeData(SyncID sid, ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception{
        int readerIndex = in.readerIndex();
        int len = in.getIntLE(readerIndex + 4);
        if (in.readableBytes() >= 8 + len) {
            in.skipBytes(8);
            ByteBuf payload = ctx.alloc().buffer(len);
            in.readBytes(payload);
            out.add(new SyncData(sid, payload));
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        int readerIndex = in.readerIndex();
        int id = in.getIntLE(readerIndex);
        SyncID sid = SyncID.findByValue(id);
        if (sid == null) {
            in.skipBytes(4);
            ctx.fireExceptionCaught(new Exception("Unknown sid: " + id));
            return;
        }
        switch (sid) {
            case OKAY:
                //fall through
            case DONE:
                decodeSid(sid, ctx, in, out);
                break;

            case FAIL:
                decodeFail(sid, ctx, in, out);
                break;

            case DATA:
                decodeData(sid, ctx, in, out);
                break;

            case LSTAT_V1:
                decodeStat(sid, ctx, in, out);
                break;

            case STAT_V2:
                decodeStatV2(sid, ctx, in, out);
                break;

            case DENT_V1:
                decodeDent(sid, ctx, in, out);
                break;

            case DENT_V2:
                decodeDentV2(sid, ctx, in, out);
                break;

            default:
                in.skipBytes(4);
                ctx.fireExceptionCaught(new Exception("Unexpected sid:" + sid));
                break;
        }
    }
}
