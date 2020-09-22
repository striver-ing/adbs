package adbs;

import adbs.device.AbstractAdbDevice;
import adbs.device.SocketAdbDevice;

public class TestAdbDevice {

    public static void main(String[] args) throws Exception {
        AbstractAdbDevice device = SocketAdbDevice.connect("127.0.0.1", 6051);
        device.connect();
        device.list("/").addListener(f -> {
            System.out.println(device);
        });
        //device.reverse("tcp:1234", "tcp:www.baidu.com:80");
    }
}
