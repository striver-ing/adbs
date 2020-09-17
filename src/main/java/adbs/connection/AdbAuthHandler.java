package adbs.connection;

import adbs.constant.Command;
import adbs.constant.Constants;
import adbs.constant.DeviceType;
import adbs.constant.Feature;
import adbs.entity.AdbPacket;
import adbs.entity.ConnectResult;
import adbs.util.AuthUtil;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.SettableFuture;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class AdbAuthHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(AdbAuthHandler.class);

    private final SettableFuture<ConnectResult> future;

    private final RSAPrivateCrtKey privateKey;

    private final byte[] publicKey;

    private final AtomicInteger state;

    public AdbAuthHandler(SettableFuture<ConnectResult> future, RSAPrivateCrtKey privateKey, byte[] publicKey) {
        this.future = future;
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.state = new AtomicInteger(0);
    }

    private void write(ChannelHandlerContext ctx, AdbPacket message) {
        ctx.writeAndFlush(message)
                .addListener(f -> {
                    if (f.cause() != null) {
                        future.setException(f.cause());
                    }
                });
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ByteBuf payload = ctx.alloc().buffer(1024);
        try {
            payload.writeCharSequence("host::features=", StandardCharsets.UTF_8);
            Feature[] features = Feature.values();
            for (int i = 0; i < features.length; i++) {
                if (i > 0) {
                    payload.writeChar(',');
                }
                payload.writeCharSequence(features[i].getCode(), StandardCharsets.UTF_8);
            }
            write(ctx, new AdbPacket(Command.A_CNXN, Constants.A_VERSION, Constants.MAX_PAYLOAD, payload));
        } catch (Exception e) {
            ReferenceCountUtil.safeRelease(payload);
            throw e;
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof AdbPacket)) {
            ReferenceCountUtil.release(msg);
            return;
        }
        AdbPacket message = (AdbPacket) msg;
        byte[] payload = null;
        if (message.payload != null) {
            payload = new byte[message.payload.readableBytes()];
            message.payload.readBytes(payload);
            ReferenceCountUtil.release(message);
        }
        switch (message.command) {
            case A_AUTH:
                if (message.arg0 != Constants.ADB_AUTH_TOKEN) {
                    future.setException(new ProtocolException("Invalid auth type: " + message.arg0));
                    return;
                }
                if (state.compareAndSet(0, 1)) {
                    if (payload.length != Constants.TOKEN_SIZE) {
                        future.setException(new ProtocolException("Invalid token size, expect=" + Constants.TOKEN_SIZE + ", actual=" + payload.length));
                        return;
                    }
                    byte[] sign = AuthUtil.sign(privateKey, payload).toByteArray();
                    ByteBuf signBuf = ctx.alloc().buffer(sign.length);
                    signBuf.writeBytes(sign);
                    write(ctx, new AdbPacket(Command.A_AUTH, Constants.ADB_AUTH_SIGNATURE, 0, signBuf));
                } else if (state.compareAndSet(1, 2)) {
                    byte[] bytes = Arrays.copyOf(publicKey, publicKey.length + 1);
                    ByteBuf keyBuf = ctx.alloc().buffer(bytes.length);
                    keyBuf.writeBytes(bytes);
                    write(ctx, new AdbPacket(Command.A_AUTH, Constants.ADB_AUTH_RSAPUBLICKEY, 0, keyBuf));
                } else {
                    future.setException(new Exception("State error:" + state));
                }
                break;

            case A_CNXN:
                //当连接上以后，把认证的handler从pipeline中移除掉
                ctx.pipeline().remove(this);

                ConnectResult devModel = new ConnectResult();
                String p = new String(payload, StandardCharsets.UTF_8);
                String[] pieces = p.split(":");
                if (pieces.length > 2) {
                    String[] props = pieces[2].split(";");
                    for(String prop : props) {
                        String[] kv = prop.split("=");
                        if (kv.length != 2) {
                            continue;
                        }
                        String key = kv[0];
                        String value = kv[1];
                        if ("ro.product.name".equals(key)) {
                            devModel.setProduct(value);
                        } else if ("ro.product.model".equals(key)) {
                            devModel.setModel(value);
                        } else if ("ro.product.device".equals(key)) {
                            devModel.setDevice(value);
                        } else if ("features".equals(key)) {
                            Set<Feature> features = new HashSet<>();
                            for(String f : value.split(",")) {
                                Feature fe = Feature.findByCode(f);
                                if (fe == null) {
                                    logger.warn("Unknown feature: " + f);
                                    continue;
                                }
                                features.add(fe);
                            }
                            devModel.setFeatures(ImmutableSet.copyOf(features));
                        }
                    }
                }
                devModel.setType(DeviceType.findByCode(pieces[0]));
                future.set(devModel);
                break;

            default:
                ctx.fireExceptionCaught(new ProtocolException("Unexpected command, expect=A_AUTH|A_CNXN, actual=" + message.command));
                break;
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        future.setException(cause);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        future.setException(new IOException("Connection closed"));
    }
}
