package adbs;

import adbs.device.AbstractAdbDevice;
import adbs.device.SocketAdbDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;

public class TestAdbDevice {

    private static final Logger logger = LoggerFactory.getLogger(TestAdbDevice.class);

    public static void main(String[] args) throws Exception {
        AbstractAdbDevice device = SocketAdbDevice.connect("127.0.0.1", 6000);
        device.connect();
        device.push(new File("D:\\tmp\\cost.csv"), "/sdcard/xx").addListener(f -> {
            logger.debug("connected {}", device);
        });
        //device.reverse("tcp:1234", "tcp:www.baidu.com:80");
    }
}
