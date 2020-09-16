package adbs.codec;

import adbs.constant.SyncID;
import adbs.entity.sync.SyncData;
import adbs.entity.sync.SyncDataDone;
import adbs.entity.sync.SyncPath;
import adbs.entity.sync.SyncQuit;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.nio.charset.StandardCharsets;

public class SyncEncoder extends MessageToByteEncoder<Object> {

    private static final byte[] PADDING = new byte[4];

    @Override
    public boolean acceptOutboundMessage(Object msg) throws Exception {
        return msg instanceof SyncPath ||
                msg instanceof SyncData ||
                msg instanceof SyncDataDone ||
                msg instanceof SyncQuit;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, ByteBuf out) throws Exception {
        if (msg instanceof SyncPath) {
            SyncPath syncPath = (SyncPath) msg;
            out.writeBytes(syncPath.sid.byteArray());
            byte[] b = syncPath.path.getBytes(StandardCharsets.UTF_8);
            out.writeIntLE(b.length);
            out.writeBytes(b);
        } else if (msg instanceof SyncData) {
            SyncData syncData = (SyncData) msg;
            out.writeBytes(SyncID.DATA.byteArray());
            out.writeIntLE(syncData.data.readableBytes());
            out.writeBytes(syncData.data);
        } else if (msg instanceof SyncDataDone) {
            SyncDataDone done = (SyncDataDone) msg;
            out.writeBytes(SyncID.DONE.byteArray());
            out.writeIntLE(done.mtime);
        } else if (msg instanceof SyncQuit) {
            out.writeBytes(SyncID.QUIT.byteArray());
            out.writeBytes(PADDING);
        }
    }
}
