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
//        File out = new File("D:\\tmp\\office.iso");
//        FileOutputStream fout = new FileOutputStream(out);
//        out.delete();
//        out.createNewFile();
//        device.pull("/sdcard/office.iso", fout).addListener(f -> {
//            fout.close();
//        });
        device.list("/sdcard").addListener(f -> {
            System.out.println(f.getNow());
        });
    }
}
