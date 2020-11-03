package adbs;

import adbs.device.AdbDevice;
import adbs.device.SmartSocketAdbDevice;
import adbs.device.SocketAdbDevice;
import adbs.util.AuthUtil;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.concurrent.TimeUnit;

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
        AdbDevice device = new SmartSocketAdbDevice("192.168.137.102", 5555, privateKey, publicKey);
        for(int i=0; i<1000; i++) {
            System.out.println(device.shell("ls", "-l", "/").get());
//            device.push(new File("D:\\tmp\\pdd.apk"), "/sdcard/pdd.apk").await();
//            device.pull("/sdcard/pdd.apk", new File("D:\\tmp\\pdd.apk.bak")).await();
//            System.out.println("success:" + i);
        }
    }
}
