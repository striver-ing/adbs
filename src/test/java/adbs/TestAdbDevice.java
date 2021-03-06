package adbs;

import adbs.device.AdbDevice;
import adbs.device.SmartSocketAdbDevice;
import adbs.exception.RemoteException;
import adbs.util.AuthUtil;
import adbs.util.SimpleHttpClient;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateCrtKey;

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

    private static String[] expandArgs(String head, String tail, String... args) {
        String[] expandArgs = new String[args.length + 2];
        expandArgs[0] = head;
        System.arraycopy(args, 0, expandArgs, 1, args.length);
        expandArgs[args.length + 1] = tail;
        return expandArgs;
    }

    public static void install(AdbDevice device, File apk, String... args) throws Exception {
        String remote = "/data/local/tmp/" + apk.getName();
        device.push(apk, remote).await();
        String result = device.shell("pm", expandArgs("install", remote, args)).get();
        result = StringUtils.trim(result);
        device.shell("rm", "-f", remote).await();
        if (!result.startsWith("Success")) {
            throw new RemoteException(result);
        }
    }

    public static void main(String[] args) throws Exception {
        AdbDevice device = new SmartSocketAdbDevice("127.0.0.1", 6056, privateKey, publicKey);
        SimpleHttpClient httpClient = new SimpleHttpClient(device, 5000);
        SimpleHttpClient.SimpleHttpResponse response = httpClient.get("/test?ok");
        String body = response.bodyAsString();
        System.out.println(body);
//        install(device, new File("D:\\tmp\\tmallandroid_10002119.apk"));
//        device.push(new File("D:\\tmp\\pdd.apk"), "/data/local/tmp/pdd.apk").get();
//        device.close();
    }
}
