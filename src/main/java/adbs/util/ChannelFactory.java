package adbs.util;

import adbs.device.AbstractAdbDevice;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;

@FunctionalInterface
public interface ChannelFactory {

    ChannelFuture newChannel(AbstractAdbDevice device, ChannelInitializer<Channel> initializer);

}
