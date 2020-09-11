package adbs.codec;

import adbs.constant.SyncID;
import adbs.entity.SyncFail;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class AdbTransportDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        int readerIndex = in.readerIndex();
        int i = in.getIntLE(readerIndex);
        SyncID sid = SyncID.findByValue(i);
        if (SyncID.FAIL.equals(sid)) {
            byte[] b = new byte[4];
            in.getBytes(readerIndex + 4, b);
            int len = Integer.valueOf(new String(b, StandardCharsets.UTF_8), 16);
            if (in.readableBytes() >= 8 + len) {
                in.skipBytes(8);
                byte[] err = new byte[len];
                in.readBytes(err);
                out.add(new SyncFail(new String(err, StandardCharsets.UTF_8)));
            }
        }
    }
}
