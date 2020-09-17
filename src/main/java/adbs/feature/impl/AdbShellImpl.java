package adbs.feature.impl;

import adbs.device.AdbDevice;
import adbs.feature.AdbShell;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInboundHandler;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static adbs.constant.Constants.DEFAULT_READ_TIMEOUT;

public class AdbShellImpl implements AdbShell {

    private final AdbDevice device;

    public AdbShellImpl(AdbDevice device) {
        this.device = device;
    }

    private static String quote(String s) {
        if (s.matches("\\S+")) {
            return s;
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static StringBuilder buildCmdLine(String cmd, String... args) {
        StringBuilder cmdLine = new StringBuilder(cmd);
        for (String arg : args) {
            cmdLine.append(" ");
            cmdLine.append(quote(arg));
        }
        return cmdLine;
    }

    public String shell(String cmd, String... args) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("shell:");
        if (cmd != null) {
            sb.append(buildCmdLine(cmd, args));
        }
        sb.append("\0");
        return device.exec(sb.toString(), DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
    }

    @Override
    public ChannelFuture shell(boolean lineFramed, ChannelInboundHandler handler) throws IOException {
        return device.open("shell:\0", channel -> {
            if (lineFramed) {
                channel.pipeline().addLast(new LineBasedFrameDecoder(8192));
            }
            channel.pipeline()
                    .addLast(new StringDecoder(StandardCharsets.UTF_8))
                    .addLast(new StringEncoder(StandardCharsets.UTF_8))
                    .addLast(handler);
        });
    }
}
