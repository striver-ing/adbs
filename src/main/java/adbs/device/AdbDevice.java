package adbs.device;

import adbs.channel.AdbChannelInitializer;
import adbs.constant.DeviceType;
import adbs.constant.Feature;
import adbs.entity.sync.SyncDent;
import adbs.entity.sync.SyncStat;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInboundHandler;
import io.netty.util.AttributeMap;
import io.netty.util.concurrent.Future;

import java.io.*;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public interface AdbDevice extends AttributeMap {

    int DEFAULT_MODE = 0664;

    /**
     * type                print bootloader | device
     * @return
     */
    DeviceType type();

    /**
     * serial             print <serial-number>
     * @return
     */
    String serial();

    String model();

    String product();

    String device();

    Set<Feature> features();

    ChannelFuture open(String destination, long timeout, TimeUnit unit, AdbChannelInitializer initializer);

    <R> Future<R> exec(String destination, long timeout, TimeUnit unit, Function<String, R> function);

    default Future<String> exec(String destination, long timeout, TimeUnit unit) {
        return exec(destination, timeout, unit, result -> result);
    }

    Future<String> shell(String cmd, String... args);

    ChannelFuture shell(boolean lineFramed, ChannelInboundHandler handler);

    ChannelFuture shell(String cmd, String[] args, boolean lineFramed, ChannelInboundHandler handler);

    Future<SyncStat> stat(String path);

    Future<SyncDent[]> list(String path);

    Future pull(String src, OutputStream dest);

    Future push(InputStream src, String dest, int mode, int mtime) throws IOException;

    default Future pull(String src, File dest) throws IOException {
        FileOutputStream os = new FileOutputStream(dest);
        try {
            return pull(src, os);
        } finally {
            os.flush();
            os.close();
        }
    }

    default Future push(File src, String dest) throws IOException {
        FileInputStream is = new FileInputStream(src);
        try {
            Long mtime = src.lastModified() / 1000;
            return push(is, dest, DEFAULT_MODE, mtime.intValue());
        } finally {
            is.close();
        }
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
     * remount [-R]
     *      remount partitions read-write. if a reboot is required, -R will
     *      will automatically reboot the device.
     */
    Future remount();

    Future reverse(String destination, AdbChannelInitializer initializer);

    Future reverse(String remote, String local);

    Future<String[]> reverseList();

    Future reverseRemove(String destination);

    Future reverseRemoveAll();

    Future close();

}
