package adbs.device;

import adbs.channel.AdbChannelInitializer;
import adbs.constant.DeviceType;
import adbs.constant.Feature;
import adbs.feature.AdbShell;
import adbs.feature.AdbSync;
import io.netty.channel.ChannelFuture;
import io.netty.util.concurrent.Future;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public interface AdbDevice extends AdbSync, AdbShell {

    Future<?> close();

    /**
     * serial             print <serial-number>
     * @return
     */
    String serial();

    String model();

    String product();

    String device();

    Set<Feature> features();

    /**
     * type                print bootloader | device
     * @return
     */
    DeviceType type();

    /**
     * root                     restart adbd with root permissions
     * @throws IOException
     */
    void root() throws IOException;

    /**
     * unroot                   restart adbd without root permissions
     * @throws IOException
     */
    void unroot() throws IOException;

    /**
     * remount [-R]
     *      remount partitions read-write. if a reboot is required, -R will
     *      will automatically reboot the device.
     * @throws IOException
     */
    void remount() throws IOException;

    String exec(String destination, long timeout, TimeUnit unit) throws IOException;

    ChannelFuture open(String destination, AdbChannelInitializer initializer) throws IOException;

    void reverse(String destination, AdbChannelInitializer initializer) throws IOException;

    List<String> reverseList() throws IOException;

    void reverseRemove(String destination) throws IOException;

    void reverseRemoveAll() throws IOException;

}
