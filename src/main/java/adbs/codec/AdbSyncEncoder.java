package adbs.codec;

import adbs.channel.AdbChannel;
import adbs.constant.Command;
import adbs.entity.AdbPacket;
import adbs.entity.SyncMessage;
import adbs.entity.SyncPath;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.util.ReferenceCountUtil;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class AdbSyncEncoder extends MessageToMessageEncoder<SyncMessage> {

    private static final byte[] PADDING = new byte[4];

    private final AdbChannel adbChannel;

    public AdbSyncEncoder(AdbChannel adbChannel) {
        this.adbChannel = adbChannel;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, SyncMessage msg, List<Object> out) throws Exception {
        ByteBuf payload = ctx.alloc().buffer(256);
        try {
            payload.writeBytes(msg.sid.byteArray());
            if (msg instanceof SyncPath) {
                byte[] b = ((SyncPath) msg).path.getBytes(StandardCharsets.UTF_8);
                payload.writeIntLE(b.length);
                payload.writeBytes(b);
            } else {
                payload.writeBytes(PADDING);
            }
            out.add(new AdbPacket(Command.A_WRTE, adbChannel.localId(), adbChannel.remoteId(), payload));
        } catch (Exception e) {
            ReferenceCountUtil.safeRelease(payload);
            throw e;
        }
    }
}
