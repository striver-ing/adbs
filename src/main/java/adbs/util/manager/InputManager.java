package adbs.util.manager;

import adbs.device.AdbDevice;

import java.io.IOException;

public class InputManager {

    private final AdbDevice device;

    private final ShellManager shell;

    public InputManager(AdbDevice device) {
        this.device = device;
        this.shell = new ShellManager(device);
    }

    public void input(String type, String... args) throws IOException {
        String[] inputArgs = new String[args.length + 1];
        inputArgs[0] = type;
        System.arraycopy(args, 0, inputArgs, 1, args.length);
        shell.shell("input", inputArgs);
    }

    public void tap(int x, int y) throws IOException {
        input("tap", String.valueOf(x), String.valueOf(y));
    }

    public void home() throws IOException {
        input("keyevent", "3");
    }

    public void menu() throws IOException {
        input("keyevent", "1");
    }

    public void back() throws IOException {
        input("keyevent", "4");
    }

    public void swipe(int xFrom, int yFrom, int xTo, int yTo, int duration) throws IOException {
        input(
                "swipe",
                String.valueOf(xFrom), String.valueOf(yFrom),
                String.valueOf(xTo), String.valueOf(yTo),
                String.valueOf(duration)
        );
    }

    public void input(String text) throws IOException {
        input("text", text);
    }

}
