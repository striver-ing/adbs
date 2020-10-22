package adbs;

import adbs.device.AdbDevice;
import adbs.device.SmartSocketAdbDevice;
import adbs.device.SocketAdbDevice;
import adbs.util.AuthUtil;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        AdbDevice device = new SmartSocketAdbDevice("192.168.137.232", 5555, privateKey, publicKey);
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
        device.close();
    }
}
