package adbs;

import adbs.channel.AdbChannelInitializer;
import adbs.constant.DeviceType;
import adbs.constant.Feature;
import adbs.device.AbstractAdbDevice;
import adbs.device.AdbDevice;
import adbs.device.SmartSocketAdbDevice;
import adbs.device.SocketAdbDevice;
import adbs.entity.ConnectResult;
import adbs.entity.sync.SyncDent;
import adbs.entity.sync.SyncStat;
import adbs.util.AuthUtil;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.usb4java.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static adbs.constant.Constants.DEFAULT_READ_TIMEOUT;

public class TestAdbDevice {

    private static final Logger logger = LoggerFactory.getLogger(TestAdbDevice.class);

    private static final RSAPrivateCrtKey privateKey;
    private static final byte[] publicKey;

    static {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        loggerContext.getLogger("root").setLevel(Level.INFO);
        try {
            privateKey = AuthUtil.loadPrivateKey("adbkey");
            publicKey = AuthUtil.generatePublicKey(privateKey).getBytes(StandardCharsets.UTF_8);
        } catch (Throwable cause) {
            throw new RuntimeException("load private key failed:" + cause.getMessage(), cause);
        }
    }

    public static void main(String[] args) throws Exception {
        AdbDevice device = new SocketAdbDevice("192.168.137.52", 5555, privateKey, publicKey);
        for(int i=0; i<10; i++) {
            try {
                device.shell("ls", "-l", "/sdcard").get();
                System.out.println("success");
            } catch (Exception e) {
                System.out.println("failed:" + e.getMessage());
            } finally {
                TimeUnit.SECONDS.sleep(5);
            }
        }
    }
}
