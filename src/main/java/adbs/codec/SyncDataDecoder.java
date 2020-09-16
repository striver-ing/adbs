package adbs.codec;

import adbs.constant.SyncID;
import adbs.entity.sync.SyncData;
import adbs.entity.sync.SyncDataDone;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import java.net.ProtocolException;
import java.util.List;

public class SyncDataDecoder extends SyncDecoder {

    @Override
    protected void decode(SyncID sid, ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        switch (sid) {
            case DATA:
                int readerIndex = in.readerIndex();
                int len = in.getIntLE(readerIndex + 4);
                if (in.readableBytes() >= 8 + len) {
                    in.skipBytes(8);
                    ByteBuf payload = ctx.alloc().buffer(len);
                    in.readBytes(payload);
                    out.add(new SyncData(payload));
                }
                break;

            case DONE:
                in.skipBytes(4);
                out.add(new SyncDataDone(in.readIntLE()));
                break;

            default:
                ctx.fireExceptionCaught(new ProtocolException("unsupported sid:" + sid));
                break;
        }
    }
}
