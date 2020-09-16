package adbs.codec;

import adbs.constant.SyncID;
import adbs.entity.sync.*;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class SyncDentDecoder extends SyncStatDecoder {

    private void decodeDent(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        int readerIndex = in.readerIndex();
        int len = in.getIntLE(readerIndex + 16);
        if (in.readableBytes() >= 20 + len) {
            in.skipBytes(4);
            SyncStat stat = decodeStat(in);
            in.skipBytes(4);
            byte[] b = new byte[len];
            in.readBytes(b);
            out.add(new SyncDent(
                    stat.mode,
                    stat.size,
                    stat.mtime,
                    new String(b, StandardCharsets.UTF_8)
            ));
        }
    }

    private void decodeDentV2(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        int readerIndex = in.readerIndex();
        int len = in.getIntLE(readerIndex + 72);
        if (in.readableBytes() >= 76 + len) {
            in.skipBytes(4);
            SyncStatV2 stat = decodeStatV2(in);
            in.skipBytes(4);
            byte[] b = new byte[len];
            in.readBytes(b);
            out.add(new SyncDentV2(stat.mode, stat.size, stat.mtime,
                    new String(b, StandardCharsets.UTF_8),
                    stat.error, stat.dev, stat.ino, stat.nlink,
                    stat.uid, stat.gid, stat.atime, stat.ctime));
        }
    }

    @Override
    protected void decode(SyncID sid, ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        switch (sid) {
            case DENT_V1:
                decodeDent(ctx, in, out);
                break;

            case DENT_V2:
                decodeDentV2(ctx, in, out);
                break;

            case DONE:
                in.skipBytes(20);
                out.add(new SyncDentDone());
                break;

            default:
                ctx.fireExceptionCaught(new ProtocolException("unsupported sid:" + sid));
                break;
        }
    }
}
