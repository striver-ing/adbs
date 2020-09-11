package adbs.connection;

import adbs.channel.*;
import adbs.constant.Command;
import adbs.device.SocketAdbDevice;
import adbs.entity.AdbPacket;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class AdbChannelProcessor extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(AdbChannelProcessor.class);

    private final SocketAdbDevice device;

    private final AtomicInteger channelIdGen;

    private final Map<Integer, FutureTransportFactory> channelFactoryMap;

    private final Map<CharSequence, TransportFactory> reverseFactoryMap;

    private final Set<String> handlers;

    public AdbChannelProcessor(
            SocketAdbDevice device,
            AtomicInteger channelIdGen,
            Map<Integer, FutureTransportFactory> channelFactoryMap,
            Map<CharSequence, TransportFactory> reverseFactoryMap
            ) {
        this.device = device;
        this.channelIdGen = channelIdGen;
        this.channelFactoryMap = channelFactoryMap;
        this.reverseFactoryMap = reverseFactoryMap;
        this.handlers = new HashSet<>();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        channelFactoryMap.clear();
        handlers.clear();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        fireEvent(ctx.pipeline(), (context, event) -> context.fireChannelInactive(), null);
        device.close(true);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        fireEvent(ctx.pipeline(), (context, event) -> context.fireExceptionCaught(event), cause);
        logger.error("Uncaught exception: {}", cause.getMessage(), cause);
        device.close(true);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        fireEvent(ctx.pipeline(), (context, event) -> context.fireChannelReadComplete(), null);
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        fireEvent(ctx.pipeline(), (context, event) -> context.fireChannelRegistered(), null);
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        fireEvent(ctx.pipeline(), (context, event) -> context.fireChannelUnregistered(), null);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        fireEvent(ctx.pipeline(), (context, event) -> context.fireChannelWritabilityChanged(), null);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        fireEvent(ctx.pipeline(), (context, event) -> context.fireUserEventTriggered(evt), evt);
    }

    public static String firstHandlerName(int localId) {
        StringBuilder sb = new StringBuilder();
        sb.append("ADB-CHANNEL#");
        sb.append(localId);
        sb.append("#FIRST");
        return sb.toString();
    }

    public static String lastHandlerName(int localId) {
        StringBuilder sb = new StringBuilder();
        sb.append("ADB-CHANNEL#");
        sb.append(localId);
        sb.append("#LAST");
        return sb.toString();
    }

    @FunctionalInterface
    private interface EventNotifier<T> {

        void notify(ChannelHandlerContext context, T event);

    }

    private <T> void fireEvent(ChannelPipeline pipeline, EventNotifier<T> notifier, T event) {
        for(String name : handlers) {
            ChannelHandlerContext ctx = pipeline.context(name);
            if (ctx != null) {
                notifier.notify(ctx, event);
            }
        }
    }

    private Transport initChannel(ChannelHandlerContext ctx, int localId, int remoteId, TransportFactory factory) {
        String firstHandler = firstHandlerName(localId);
        String lastHandler = lastHandlerName(localId);
        AdbChannel channel = new AdbChannel(ctx.channel(), localId, remoteId);
        ctx.pipeline().addLast(firstHandler, new ChannelDuplexHandler(){
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                if (
                        msg instanceof ByteBuf ||
                                msg instanceof AdbPacket
                ) {
                    //只有这两种类型的才继续往上流，否则直接丢弃掉，避免写到错误的adb channel去了
                    super.write(ctx, msg, promise);
                } else {
                    if (msg instanceof ReferenceCounted) {
                        ReferenceCounted rc = (ReferenceCounted) msg;
                        if (rc.refCnt() > 0) {
                            rc.release(rc.refCnt());
                        }
                    }
                    Exception ex = new Exception("Reached first handler");
                    promise.tryFailure(ex);
                    logger.error("[{}:{}] {}", device.serial(), localId, ex.getMessage());
                }
            }
        });
        ctx.pipeline().addAfter(firstHandler, lastHandler, new ChannelInboundEnding(device.serial(), localId));
        Transport transport = factory.factory(channel);
        handlers.add(firstHandler);
        ctx.pipeline()
                .context(firstHandlerName(localId))
                .fireChannelRegistered()
                .fireChannelActive();

        return transport;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof AdbPacket)) {
            ReferenceCountUtil.release(msg);
            return;
        }
        AdbPacket message = (AdbPacket) msg;
        if (Command.A_OPEN.equals(message.command)) {
            //启动反向代理的时候，adb daemon会给连接发送OPEN指令，用于创建一条通道
            try {
                int remoteId = message.arg0;
                int localId = channelIdGen.getAndIncrement();
                //-1是因为最后有一个\0
                byte[] payload = new byte[message.size - 1];
                message.payload.readBytes(payload);
                String destination = new String(payload, StandardCharsets.UTF_8);
                TransportFactory factory = reverseFactoryMap.get(destination);
                initChannel(ctx, localId, remoteId, factory);
                ctx.writeAndFlush(new AdbPacket(Command.A_OKAY, localId, remoteId, null))
                        .addListener(f -> {
                            if (f.cause() != null) {
                                logger.error("[{}:{}] Send OKAY failed, error={}", device.serial(), localId, f.cause().getMessage(), f.cause());
                            }
                        });
            } catch (Throwable cause) {
                ctx.writeAndFlush(new AdbPacket(Command.A_CLSE, 0, message.arg0, null))
                        .addListener(f -> {
                            if (f.cause() != null) {
                                logger.error("[{}:{}] Send CLSE failed, error={}", device.serial(), 0, f.cause().getMessage(), f.cause());
                            }
                        });
            } finally {
                ReferenceCountUtil.safeRelease(message);
            }
        } else if (Command.A_OKAY.equals(message.command)) {
            FutureTransportFactory factory = channelFactoryMap.remove(message.arg1);
            if (factory != null) {
                Transport transport = initChannel(ctx, message.arg1, message.arg0, factory);
                factory.future().set(transport);
            } else {
                ctx.pipeline().context(firstHandlerName(message.arg1)).fireUserEventTriggered("ACK");
            }
        } else if (Command.A_CLSE.equals(message.command)) {
            FutureTransportFactory factory = channelFactoryMap.remove(message.arg1);
            if (factory != null) {
                //destination打开失败的情况
                factory.future().setException(new Exception("Channel closed"));
            } else {
                //碰到Close的时候把first到last之间的都删除掉
                ctx.writeAndFlush(new AdbPacket(Command.A_CLSE, message.arg1, message.arg0, null))
                        .addListener(f -> {
                            if (f.cause() != null) {
                                logger.error("[{}:{}] Send CLSE failed, error={}", device.serial(), message.arg1, f.cause().getMessage(), f.cause());
                            }
                        });
                String firstName = firstHandlerName(message.arg1);
                String lastName = lastHandlerName(message.arg1);
                ChannelPipeline pipeline = ctx.pipeline();
                ChannelHandlerContext first = null;
                List<String> names = new ArrayList<>();
                for (String name : pipeline.names()) {
                    if (firstName.equals(name)) {
                        first = pipeline.context(name);
                    }
                    if (first != null) {
                        names.add(name);
                    }
                    if (lastName.equals(name)) {
                        break;
                    }
                }
                if (first != null) {
                    handlers.remove(firstName);
                    first.fireChannelUnregistered()
                            .fireChannelInactive();
                    for(String name : names) {
                        pipeline.remove(name);
                    }
                }
            }
        } else if (Command.A_WRTE.equals(message.command)) {
            ctx.writeAndFlush(new AdbPacket(Command.A_OKAY, message.arg1, message.arg0, null))
                    .addListener(f -> {
                        if (f.cause() != null) {
                            logger.error("[{}:{}] Send OKAY failed, error={}", device.serial(), message.arg1, f.cause().getMessage(), f.cause());
                        }
                    });
            ctx.pipeline()
                    .context(firstHandlerName(message.arg1))
                    .fireChannelRead(message.payload);
        } else {
            throw new Exception("Unexpected channel command:" + message.command);
        }
    }

    private static class ChannelInboundEnding implements ChannelInboundHandler {

        private final String serial;

        private final int localId;

        public ChannelInboundEnding(String serial, int localId) {
            this.serial = serial;
            this.localId = localId;
        }

        @Override
        public void channelRegistered(ChannelHandlerContext ctx) throws Exception {

        }

        @Override
        public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {

        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {

        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {

        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof ReferenceCounted) {
                ReferenceCountUtil.safeRelease(msg, ((ReferenceCounted) msg).refCnt());
            }
            logger.warn("[{}:{}] Reached last handler", serial, localId);
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {

        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {

        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {

        }

        @Override
        @SuppressWarnings("deprecation")
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {

        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception {

        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {

        }
    }
}
