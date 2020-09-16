package adbs.channel;

import io.netty.channel.Channel;

@FunctionalInterface
public interface AdbChannelInitializer {

    void initChannel(Channel channel);

}
