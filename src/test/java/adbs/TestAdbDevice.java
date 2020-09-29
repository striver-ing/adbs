package adbs;

import adbs.device.DefaultAdbDevice;
import adbs.device.SocketAdbDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestAdbDevice {

    private static final Logger logger = LoggerFactory.getLogger(TestAdbDevice.class);

    public static void main(String[] args) throws Exception {
        DefaultAdbDevice device = SocketAdbDevice.connect("127.0.0.1", 6000);
        device.reverse("tcp:1234", "tcp:1234");
    }
}
