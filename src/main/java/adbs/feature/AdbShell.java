package adbs.feature;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInboundHandler;

import java.io.IOException;

public interface AdbShell {

    String exec(String cmd, String... args) throws IOException;

    ChannelFuture shell(boolean lineFramed, ChannelInboundHandler handler) throws IOException;
}
