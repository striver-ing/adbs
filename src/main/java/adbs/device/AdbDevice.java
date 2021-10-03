package adbs.device;

import adbs.channel.AdbChannelInitializer;
import adbs.constant.DeviceType;
import adbs.constant.Feature;
import adbs.entity.sync.SyncDent;
import adbs.entity.sync.SyncStat;
import io.netty.channel.*;
import io.netty.util.AttributeMap;
import io.netty.util.concurrent.Future;

import java.io.*;
import java.util.Set;

public interface AdbDevice extends AttributeMap {

    int DEFAULT_MODE = 0664;

    boolean isClosed();

    /**
     * serial             print <serial-number>
     * @return
     */
    String serial();

    /**
     * type                print bootloader | device
     * @return
     */
    DeviceType type();

    String model();

    String product();

    String device();

    Set<Feature> features();

    EventLoop executor();

    Future<Channel> open(String destination, int timeoutMs, AdbChannelInitializer initializer);

    default Future<Channel> open(String destination, AdbChannelInitializer initializer) {
        return open(destination, 30000, initializer);
    }

    Future<String> exec(String destination, int timeoutMs);

    default Future<String> exec(String destination) {
        return exec(destination, 30000);
    }

    Future<String> shell(String cmd, int timeoutMs, String... args);

    default Future<String> shell(String cmd, String... args) {
        return shell(cmd, 0, args);
    }

    Future<Channel> shell(boolean lineFramed, ChannelInboundHandler handler);

    Future<Channel> shell(String cmd, String[] args, boolean lineFramed, ChannelInboundHandler handler);

    Future<SyncStat> stat(String path);

    Future<SyncDent[]> list(String path);

    Future pull(String src, OutputStream dest);

    Future push(InputStream src, String dest, int mode, int mtime);

    default Future pull(String src, File dest) throws IOException {
        FileOutputStream os = new FileOutputStream(dest);
        return pull(src, os).addListener(f -> {
            os.flush();
            os.close();
        });
    }

    default Future push(File src, String dest) throws IOException {
        FileInputStream is = new FileInputStream(src);
        Long mtime = src.lastModified() / 1000;
        return push(is, dest, DEFAULT_MODE, mtime.intValue())
                .addListener(f -> {
                    is.close();
                });
    }

    /**
     * root                     restart adbd with root permissions
     */
    Future root();

    /**
     * unroot                   restart adbd without root permissions
     */
    Future unroot();

    /**
     * remount partitions read-write.
     */
    Future remount();

    /**
     * usb                      restart adbd listening on USB
     * tcpip PORT               restart adbd listening on TCP on PORT
     * @param port
     * @return
     */
    Future reload(int port);

    Future<String> reverse(String destination, AdbChannelInitializer initializer);

    Future<String> reverse(String remote, String local);

    Future<String[]> reverseList();

    Future reverseRemove(String destination);

    Future reverseRemoveAll();

    Future close();

}
