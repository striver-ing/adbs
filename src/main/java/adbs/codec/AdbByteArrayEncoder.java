package adbs.codec;

import adbs.channel.AdbChannel;
import adbs.util.CodecUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import io.netty.handler.codec.string.StringEncoder;

import java.nio.charset.Charset;
import java.util.List;

public class AdbByteArrayEncoder extends ByteArrayEncoder {

    private final AdbChannel channel;

    public AdbByteArrayEncoder(AdbChannel channel) {
        this.channel = channel;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, byte[] msg, List<Object> out) throws Exception {
        super.encode(ctx, msg, out);
        CodecUtil.afterEncode(channel, out);
    }
}
