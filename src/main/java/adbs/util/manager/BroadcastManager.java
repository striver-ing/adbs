package adbs.util.manager;

import adbs.device.AdbDevice;
import adbs.exception.RemoteException;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;

public class BroadcastManager {

    private final AdbDevice device;

    private final ShellManager shell;

    public BroadcastManager(AdbDevice device) {
        this.device = device;
        this.shell = new ShellManager(device);
    }

    public void broadcast(String action, String data) throws IOException {
        String result = shell.shell("am", "broadcast", "-a", action, "-d", data);
        result = StringUtils.trim(result);
        if (result.indexOf("Broadcast completed") == -1) {
            throw new RemoteException(result);
        }
    }

    public void broadcast(String action) throws IOException {
        String result = shell.shell("am", "broadcast", "-a", action);
        result = StringUtils.trim(result);
        if (result.indexOf("Broadcast completed") == -1) {
            throw new RemoteException(result);
        }
    }

}
