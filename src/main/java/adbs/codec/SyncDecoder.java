package adbs.codec;

import adbs.constant.SyncID;
import adbs.entity.sync.SyncFail;
import adbs.entity.sync.SyncOkay;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class SyncDecoder extends ByteToMessageDecoder {

    protected void decode(SyncID sid, ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        ctx.fireExceptionCaught(new ProtocolException("unsupported sid:" + sid));
    }

    @Override
    protected final void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
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
                in.skipBytes(8);
                out.add(new SyncOkay());
                break;

            case FAIL:
                int len = in.getIntLE(readerIndex + 4);
                if (in.readableBytes() >= 8 + len) {
                    byte[] b = new byte[len];
                    in.skipBytes(8);
                    in.readBytes(b);
                    out.add(new SyncFail(new String(b, StandardCharsets.UTF_8)));
                }
                break;

            default:
                decode(sid, ctx, in, out);
                break;
        }
    }
}
