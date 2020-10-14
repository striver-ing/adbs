package adbs;

import adbs.device.DefaultAdbDevice;
import adbs.device.SocketAdbDevice;
import adbs.entity.sync.SyncStat;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestAdbDevice {

    private static final Logger logger = LoggerFactory.getLogger(TestAdbDevice.class);

    public static void main(String[] args) throws Exception {
        DefaultAdbDevice device = SocketAdbDevice.connect("127.0.0.1", 6058);
        //String res = device.shell("busybox", "ps", "-ef", "|", "grep", "adbd").get();
        SyncStat stat = device.stat("/system/xbin/busybox").get();
        System.out.println(stat);
    }
}
