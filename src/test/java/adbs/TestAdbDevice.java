package adbs;

import adbs.device.AdbDevice;
import adbs.device.DefaultAdbDevice;
import adbs.device.SocketAdbDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

public class TestAdbDevice {

    private static final Logger logger = LoggerFactory.getLogger(TestAdbDevice.class);

    public static void main(String[] args) throws Exception {
        DefaultAdbDevice device = SocketAdbDevice.connect("127.0.0.1", 6000);
        device.connectFuture().get();
        System.out.println("device connected");
        Long now = System.currentTimeMillis() / 1000;
        device.push(new FileInputStream("D:\\tmp\\test.iso"), "/sdcard/xx.exe", AdbDevice.DEFAULT_MODE, now.intValue()).get();
        System.out.println("push success");
        File out = new File("D:\\xx.exe");
        out.delete();
        out.createNewFile();
        device.pull("/sdcard/xx.exe", new FileOutputStream(out)).get();
        System.out.println("pull success");
    }
}
