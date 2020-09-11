package adbs.codec;

import adbs.channel.AdbChannel;
import adbs.util.CodecUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.string.StringEncoder;

import java.nio.charset.Charset;
import java.util.List;

public class AdbStringEncoder extends StringEncoder {

    private final AdbChannel channel;

    public AdbStringEncoder(AdbChannel channel) {
        this.channel = channel;
    }

    public AdbStringEncoder(Charset charset, AdbChannel channel) {
        super(charset);
        this.channel = channel;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, CharSequence msg, List<Object> out) throws Exception {
        super.encode(ctx, msg, out);
        CodecUtil.afterEncode(channel, out);
    }
}
