package adbs.device;

import adbs.channel.AdbChannel;
import adbs.channel.AdbChannelAddress;
import adbs.channel.AdbChannelInitializer;
import adbs.connection.AdbChannelProcessor;
import adbs.constant.DeviceType;
import adbs.constant.Feature;
import adbs.entity.ConnectResult;
import adbs.entity.sync.SyncDent;
import adbs.entity.sync.SyncStat;
import adbs.exception.RemoteException;
import adbs.feature.AdbShell;
import adbs.feature.AdbSync;
import adbs.feature.impl.AdbShellImpl;
import adbs.feature.impl.AdbSyncImpl;
import adbs.util.ChannelFactory;
import adbs.util.ChannelUtil;
import com.google.common.util.concurrent.SettableFuture;
import io.netty.channel.*;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static adbs.constant.Constants.DEFAULT_READ_TIMEOUT;

public abstract class AbstractAdbDevice implements AdbDevice {

    private static final Logger logger = LoggerFactory.getLogger(AbstractAdbDevice.class);

    private final String serial;

    private final ChannelFactory factory;

    private final Map<CharSequence, AdbChannelInitializer> reverseMap;

    private final AtomicInteger channelIdGen;

    private final AdbSync sync;

    private final AdbShell shell;

    //设备连接信息
    private volatile Channel connection;

    private volatile DeviceType type;

    private volatile String model;

    private volatile String product;

    private volatile String device;

    private volatile Set<Feature> features;

    public AbstractAdbDevice(String serial, ChannelFactory factory) throws IOException {
        this.serial = serial;
        this.factory = factory;
        this.reverseMap = new ConcurrentHashMap<>();
        this.channelIdGen = new AtomicInteger(1);
        this.sync = new AdbSyncImpl(this);
        this.shell = new AdbShellImpl(this);
        this.connect();
    }

    @Override
    public String serial() {
        return serial;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public String product() {
        return product;
    }

    @Override
    public String device() {
        return device;
    }

    @Override
    public Set<Feature> features() {
        return features;
    }

    @Override
    public DeviceType type() {
        return type;
    }

    private void connect() throws IOException {
        SettableFuture<ConnectResult> future = SettableFuture.create();
        this.connection = factory.newChannel(future);
        this.connection.pipeline().addFirst(new ChannelInboundHandlerAdapter(){
            @Override
            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                ChannelPipeline p = ctx.pipeline();
                if (p.get(AdbChannelProcessor.class) != null) {
                    p.remove(AdbChannelProcessor.class);
                }
                p.addLast("handler", new AdbChannelProcessor(AbstractAdbDevice.this, channelIdGen, reverseMap));
                ctx.fireChannelActive();
            }
        });
        try {
            ConnectResult result = future.get(DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
            this.type = result.getType();
            this.model = result.getModel();
            this.product = result.getProduct();
            this.device = result.getDevice();
            this.features = result.getFeatures();
        } catch (Throwable cause) {
            close();
            throw new IOException("Connect to " + serial + " failed: " + cause.getMessage(), cause);
        }
    }

    @Override
    public ChannelFuture open(String destination, AdbChannelInitializer initializer) throws IOException {
        int localId = channelIdGen.getAndIncrement();
        String channelName = ChannelUtil.getChannelName(localId);
        AdbChannel channel = new AdbChannel(connection, localId, 0);
        initializer.initChannel(channel);
        connection.pipeline().addLast(channelName, channel);
        try {
            return channel.connect(new AdbChannelAddress(destination, localId));
        } catch (Throwable cause) {
            connection.pipeline().remove(channelName);
            throw new IOException("Open destination `"+destination+"` failed: " + cause.getMessage(), cause);
        }
    }

    @Override
    public String exec(String destination, long timeout, TimeUnit unit) throws IOException {
        SettableFuture<String> future = SettableFuture.create();
        StringBuilder sb = new StringBuilder();
        ChannelFuture cf = open(destination, channel -> {
            channel.pipeline()
                    .addLast(new StringDecoder(StandardCharsets.UTF_8))
                    .addLast(new StringEncoder(StandardCharsets.UTF_8))
                    .addLast(new ChannelInboundHandlerAdapter(){
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                            if (msg instanceof CharSequence) {
                                sb.append(msg);
                            } else {
                                ctx.fireChannelRead(msg);
                            }
                        }

                        @Override
                        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                            future.set(sb.toString());
                        }
                    });
        });
        try {
            return future.get(timeout, unit);
        } catch (Throwable cause) {
            throw new IOException("exec `" + destination + "` failed: " + cause.getMessage(), cause);
        } finally {
            cf.channel().close();
        }
    }

    private static void assertResult(String result) throws IOException {
        if (result.startsWith("FAIL")) {
            int len = Integer.valueOf(result.substring(4, 8), 16);
            throw new RemoteException(result.substring(8, 8 + len));
        } else if (!"OKAY".equals(result)) {
            throw new ProtocolException("unknown reply: " + result);
        }
    }

    private Future addReconnectHandler() {
        SettableFuture future = SettableFuture.create();
        connection.pipeline().addAfter("handler", "reconnect", new ChannelInboundHandlerAdapter(){
            @Override
            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                ctx.fireChannelInactive();
                future.set(null);
            }
        });
        return future;
    }

    private void waitForReconnect(Future future) throws IOException {
        //等待adb daemon重启
        try {
            future.get(DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
        } catch (Throwable cause) {
            throw new IOException("reconnect to `" + serial + "` failed:" + cause.getMessage(), cause);
        }
        while (true) {
            try {
                this.connect();
                logger.info("reconnected to `{}`", serial);
                break;
            } catch (Throwable cause) {
                logger.error("reconnect to `{}` failed:{}", serial, cause.getMessage());
            }
        }
    }

    @Override
    public void root() throws IOException {
        Future future = addReconnectHandler();
        String result = exec("root:\0", DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
        result = StringUtils.trim(result);
        if ("restarting adbd as root".equals(result)) {
            waitForReconnect(future);
        } else if ("adbd is already running as root".equals(result)) {
            connection.pipeline().remove("reconnect");
        } else {
            waitForReconnect(future);
        }
    }

    @Override
    public void unroot() throws IOException {
        Future future = addReconnectHandler();
        String result = exec("unroot:\0", DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
        result = StringUtils.trim(result);
        if ("restarting adbd as non root".equals(result)) {
            waitForReconnect(future);
        } else if ("adbd not running as root".equals(result)) {
            connection.pipeline().remove("reconnect");
        } else {
            waitForReconnect(future);
        }
    }

    @Override
    public void remount() throws IOException {
        String result = exec("remount:\0", DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
        result = StringUtils.trim(result);
        if (!"remount succeeded".equals(result)) {
            throw new RemoteException(result);
        }
    }

    @Override
    public SyncStat stat(String path) throws IOException {
        return sync.stat(path);
    }

    @Override
    public SyncDent[] list(String path) throws IOException {
        return sync.list(path);
    }

    @Override
    public int pull(String src, OutputStream dest) throws IOException {
        return sync.pull(src, dest);
    }

    @Override
    public int push(InputStream src, String dest, int mode, int mtime) throws IOException {
        return sync.push(src, dest, mode, mtime);
    }

    @Override
    public String shell(String cmd, String... args) throws IOException {
        return shell.shell(cmd, args);
    }

    @Override
    public ChannelFuture shell(boolean lineFramed, ChannelInboundHandler handler) throws IOException {
        return shell.shell(lineFramed, handler);
    }

    @Override
    public void reverse(String destination, AdbChannelInitializer initializer) throws IOException {
        String cmd = "reverse:forward:" + destination + ";" + destination + "\0";
        String result = exec(cmd, DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
        assertResult(result);
        reverseMap.put(destination, initializer);
    }

    @Override
    public List<String> reverseList() throws IOException {
        String result = exec("reverse:list-forward\0", DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
        int len = Integer.valueOf(result.substring(0, 4), 16);
        //-1是因为最后有一个\0
        result = result.substring(4, len + 4 - 1);
        String[] lines = result.split("\n");
        return Arrays.stream(lines)
                .map(line -> StringUtils.trim(line))
                .filter(line -> StringUtils.isNotEmpty(line))
                .collect(Collectors.toList());
    }

    @Override
    public void reverseRemove(String destination) throws IOException {
        String result = exec("reverse:killforward:" + destination + "\0", DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
        assertResult(result);
        reverseMap.remove(destination);
    }

    @Override
    public void reverseRemoveAll() throws IOException {
        String result = exec("reverse:killforward-all\0", DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
        assertResult(result);
        reverseMap.clear();
    }

    protected abstract void doClose();

    @Override
    public io.netty.util.concurrent.Future<?> close() {
        return this.connection.close().addListener(f -> {
            doClose();
            if (f.cause() == null) {
                logger.info("connection `{}` closed", serial);
            } else {
                logger.error("connection `{}` close error:{}", serial, f.cause().getMessage(), f.cause());
            }
        });
    }
}
