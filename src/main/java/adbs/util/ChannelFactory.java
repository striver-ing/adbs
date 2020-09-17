package adbs.util;

import adbs.entity.ConnectResult;
import com.google.common.util.concurrent.SettableFuture;
import io.netty.channel.Channel;

@FunctionalInterface
public interface ChannelFactory {

    Channel newChannel(SettableFuture<ConnectResult> future);

}
