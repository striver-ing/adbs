package adbs.util;

public class ShellUtil {

    private static String quote(String s) {
        if (s.matches("\\S+")) {
            return s;
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }

    public static StringBuilder buildCmdLine(String cmd, String... args) {
        StringBuilder cmdLine = new StringBuilder(cmd);
        for (String arg : args) {
            cmdLine.append(" ");
            cmdLine.append(quote(arg));
        }
        return cmdLine;
    }
}
