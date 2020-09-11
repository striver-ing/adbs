package adbs.codec;

import adbs.channel.AdbChannel;
import adbs.util.CodecUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequestEncoder;

import java.util.List;

public class AdbHttpRequestEncoder extends HttpRequestEncoder {

    private final AdbChannel adbChannel;

    public AdbHttpRequestEncoder(AdbChannel adbChannel) {
        this.adbChannel = adbChannel;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Object msg, List<Object> out) throws Exception {
        super.encode(ctx, msg, out);
        CodecUtil.afterEncode(adbChannel, out);
    }
}
