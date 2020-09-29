package adbs.connection;

import adbs.channel.AdbChannel;
import adbs.channel.AdbChannelAddress;
import adbs.channel.AdbChannelInitializer;
import adbs.constant.Command;
import adbs.device.AdbDevice;
import adbs.entity.AdbPacket;
import adbs.util.ChannelUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class AdbChannelProcessor extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(AdbChannelProcessor.class);

    private final AdbDevice device;

    private final AtomicInteger channelIdGen;

    private final Map<CharSequence, AdbChannelInitializer> reverseMap;

    public AdbChannelProcessor(
            AdbDevice device,
            AtomicInteger channelIdGen,
            Map<CharSequence, AdbChannelInitializer> reverseMap
            ) {
        this.device = device;
        this.channelIdGen = channelIdGen;
        this.reverseMap = reverseMap;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("Uncaught exception: {}", cause.getMessage(), cause);
        ctx.fireExceptionCaught(cause);
    }

    private boolean fireChannelMessage(ChannelHandlerContext ctx, AdbPacket message) throws Exception {
        String handlerName = ChannelUtil.getChannelName(message.arg1);
        ChannelHandlerContext channelContext = ctx.pipeline().context(handlerName);
        if (channelContext != null) {
            ChannelInboundHandler handler = (ChannelInboundHandler) channelContext.handler();
            handler.channelRead(channelContext, message);
            return true;
        } else {
            //这里将引用释放掉，避免泄漏
            ReferenceCountUtil.safeRelease(message);
            return false;
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof AdbPacket)) {
            ReferenceCountUtil.release(msg);
            return;
        }
        AdbPacket message = (AdbPacket) msg;
        switch (message.command) {

            case A_OPEN:
                int remoteId = message.arg0;
                int localId = channelIdGen.getAndIncrement();
                try {
                    //-1是因为最后有一个\0
                    byte[] payload = new byte[message.size - 1];
                    message.payload.readBytes(payload);
                    String destination = new String(payload, StandardCharsets.UTF_8);
                    AdbChannelInitializer initializer = reverseMap.get(destination);
                    String channelName = ChannelUtil.getChannelName(localId);
                    AdbChannel channel = new AdbChannel(ctx.channel(), 0, remoteId);
                    channel.bind(new AdbChannelAddress(destination, localId)).addListener(f -> {
                        if (f.cause() == null) {
                            try {
                                initializer.initChannel(channel);
                                ctx.pipeline().addLast(channelName, channel);
                                ctx.writeAndFlush(new AdbPacket(Command.A_OKAY, localId, remoteId));
                                channel.eventLoop().register(channel);
                            } catch (Throwable cause) {
                                ctx.writeAndFlush(new AdbPacket(Command.A_CLSE, 0, remoteId));
                            }
                        } else {
                            ctx.writeAndFlush(new AdbPacket(Command.A_CLSE, 0, remoteId));
                        }
                    });
                } catch (Throwable cause) {
                    ctx.writeAndFlush(new AdbPacket(Command.A_CLSE, 0, remoteId));
                } finally {
                    ReferenceCountUtil.safeRelease(message);
                }
                break;

            case A_OKAY:
                fireChannelMessage(ctx, message);
                break;

            case A_WRTE:
                ctx.writeAndFlush(new AdbPacket(Command.A_OKAY, message.arg1, message.arg0));
                fireChannelMessage(ctx, message);
                break;

            case A_CLSE:
                /**
                 * 如果成功转发了事件，那么AdbChannel#doClose的时候已经发送了CLSE命令了，不需要重复发送
                 * @see AdbChannel#doClose()
                 */
                if (!fireChannelMessage(ctx, message)) {
                    ctx.writeAndFlush(new AdbPacket(Command.A_CLSE, message.arg1, message.arg0));
                }
                break;

            default:
                ctx.fireExceptionCaught(new Exception("Unexpected channel command:" + message.command));
                break;

        }
    }
}
