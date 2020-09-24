package adbs.util;

public class ShellUtil {

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

    public static String buildShellCmd(String cmd, String[] args) {
        StringBuilder sb = new StringBuilder();
        sb.append("shell:");
        if (cmd != null) {
            sb.append(buildCmdLine(cmd, args));
        }
        sb.append("\0");
        return sb.toString();
    }
}
