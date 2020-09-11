package adbs.client;

import adbs.device.AdbDevice;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static adbs.constant.Constants.DEFAULT_READ_TIMEOUT;

public class ShellClient {

    private final AdbDevice device;

    public ShellClient(AdbDevice device) {
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

    public String exec(String cmd, String... args) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("shell:");
        if (cmd != null) {
            sb.append(buildCmdLine(cmd, args));
        }
        sb.append("\0");
        return device.exec(sb.toString(), DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
    }

    //TODO 后续可以考虑实现interactive模式
}
