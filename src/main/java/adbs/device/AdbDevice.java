package adbs.device;

import adbs.channel.FutureTransportFactory;
import adbs.channel.Transport;
import adbs.channel.TransportFactory;
import adbs.constant.DeviceType;
import adbs.constant.Feature;
import io.netty.util.concurrent.Future;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public interface AdbDevice {

    Future<?> close(boolean autoReconnect);

    default Future<?> close() {
        return close(false);
    }

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

    <I, O> Transport<I, O> open(String destination, long timeout, TimeUnit unit, FutureTransportFactory<I, O> factory) throws IOException;

    void reverse(String destination, TransportFactory factory) throws IOException;

}
