package adbs.util;

import io.netty.channel.*;

public interface ChannelFactory {

    ChannelFuture newChannel(ChannelInitializer<Channel> initializer);

}
