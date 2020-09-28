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
//        device.connectFuture().await();
//        System.out.println("device connected");
//        Long now = System.currentTimeMillis() / 1000;
//        device.push(new FileInputStream("D:\\office2016（excel、word、ppt、outlook、onenote）\\SW_DVD5_Office_2016_64Bit_ChnSimp_MLF_X20-42470.ISO"), "/sdcard/office.iso", AdbDevice.DEFAULT_MODE, now.intValue()).await();
//        System.out.println("push success");
        File out = new File("D:\\tmp\\office.iso");
        FileOutputStream fout = new FileOutputStream(out);
        out.delete();
        out.createNewFile();
        device.pull("/sdcard/office.iso", fout).await();
        fout.close();
        System.out.println("pull success");
    }
}
