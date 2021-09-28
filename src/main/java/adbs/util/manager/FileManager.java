package adbs.util.manager;

import adbs.device.AdbDevice;
import adbs.entity.sync.SyncDent;
import adbs.exception.RemoteException;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class FileManager {

    private final AdbDevice device;

    private final BroadcastManager bm;

    private final ShellManager shell;

    public FileManager(AdbDevice device) {
        this.device = device;
        this.bm = new BroadcastManager(device);
        this.shell = new ShellManager(device);
    }

    public void pull(String src, OutputStream dest) {
        try {
            device.pull(src, dest).get();
        } catch (Throwable cause) {
            throw new RuntimeException(cause.getMessage(), cause);
        }
    }

    public void push(InputStream src, String dest, int mode, int mtime) {
        try {
            device.push(src, dest, mode, mtime).get();
        } catch (Throwable cause) {
            throw new RuntimeException(cause.getMessage(), cause);
        }
    }

    public void pull(String src, File dest) {
        try {
            device.pull(src, dest).get();
        } catch (Throwable cause) {
            throw new RuntimeException(cause.getMessage(), cause);
        }
    }

    public void push(File src, String dest) {
        try {
            device.push(src, dest).get();
        } catch (Throwable cause) {
            throw new RuntimeException(cause.getMessage(), cause);
        }
    }

    public void delete(String path) throws IOException {
        String result = shell.shell("rm", "-f", path);
        result = StringUtils.trim(result);
        if (StringUtils.isNotEmpty(result)) {
            throw new RemoteException(result);
        }
    }

    public SyncDent[] list(String path) {
        try {
            return device.list(path).get();
        } catch (Throwable cause) {
            throw new RuntimeException(cause.getMessage(), cause);
        }
    }

    public void pushMedia(InputStream source, String remote, int mode, int lastModified) throws IOException {
        push(source, remote, mode, lastModified);
        bm.broadcast("android.intent.action.MEDIA_SCANNER_SCAN_FILE", "file://" + remote);
    }

    public void pushMedia(File local, String remote) throws IOException {
        push(local, remote);
        bm.broadcast("android.intent.action.MEDIA_SCANNER_SCAN_FILE", "file://" + remote);
    }

    public void deleteMedia(String path) throws IOException {
        delete(path);
        bm.broadcast("android.intent.action.MEDIA_SCANNER_SCAN_FILE", "file://" + path);
    }

    public void mkdir(String path) {
        shell.shell("mkdir", path);
    }

    public void chmod(String path, String mod) {
        shell.shell("chmod", mod, path);
    }
}
