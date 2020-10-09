package adbs.connection;

import adbs.constant.Command;
import adbs.entity.AdbPacket;
import adbs.util.MessageUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ProtocolException;
import java.util.List;

public class AdbPacketCodec extends ByteToMessageCodec<AdbPacket> {

    private static final Logger logger = LoggerFactory.getLogger(AdbPacketCodec.class);

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        //读取长度
        in.markReaderIndex();
        if (in.readableBytes() < 16) {
            in.resetReaderIndex();
            return;
        }
        int cmd = in.readIntLE();
        int arg0 = in.readIntLE();
        int arg1 = in.readIntLE();
        int len = in.readIntLE();
        if (in.readableBytes() < 8/*剩余头的长度*/ + len) {
            in.resetReaderIndex();
            return;
        }
        int checksum = in.readIntLE();
        int magic = in.readIntLE();
        Command command = Command.findByValue(cmd);
        if (command == null) {
            ctx.fireExceptionCaught(new ProtocolException("Unknown command " + command.name()));
            return;
        }
        if (command.magic() != magic) {
            ctx.fireExceptionCaught(new ProtocolException("Unmatched magic field expect=" + command.magic() + ", actual=" + magic));
            return;
        }
        ByteBuf payload = null;
        if (len > 0) {
            payload = ctx.alloc().buffer(len);
            in.readBytes(payload);
            int actualChecksum = MessageUtil.checksum(payload);
            if (actualChecksum != checksum) {
                ReferenceCountUtil.release(payload);
                ctx.fireExceptionCaught(new ProtocolException("Checksum error expect=" + checksum + ", actual=" + actualChecksum));
                return;
            }
        }
        AdbPacket message = new AdbPacket(command, arg0, arg1, len, checksum, magic, payload);
        if (logger.isDebugEnabled()) {
            logger.debug("<== recv command={}, arg0={}, arg1={}, size={}",
                    message.command, message.arg0, message.arg1, message.size);
        }
        out.add(message);
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, AdbPacket msg, ByteBuf out) throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("==> send command={}, arg0={}, arg1={}, size={}",
                    msg.command, msg.arg0, msg.arg1, msg.size);
        }
        out.writeIntLE(msg.command.value());
        out.writeIntLE(msg.arg0);
        out.writeIntLE(msg.arg1);
        out.writeIntLE(msg.size);
        out.writeIntLE(msg.checksum);
        out.writeIntLE(msg.magic);
        if (msg.payload != null) {
            out.writeBytes(msg.payload);
        }
    }
}
