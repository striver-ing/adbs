package adbs.util;

import adbs.channel.AdbChannelInitializer;
import adbs.device.DefaultAdbDevice;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;

@FunctionalInterface
public interface ChannelFactory {

    ChannelFuture newChannel(DefaultAdbDevice device, EventLoopGroup eventLoop, AdbChannelInitializer initializer);

}
