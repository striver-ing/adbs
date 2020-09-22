package adbs.util;

import adbs.channel.AdbChannelInitializer;
import adbs.device.AbstractAdbDevice;
import io.netty.channel.ChannelFuture;

@FunctionalInterface
public interface ChannelFactory {

    ChannelFuture newChannel(AbstractAdbDevice device, AdbChannelInitializer initializer);

}
