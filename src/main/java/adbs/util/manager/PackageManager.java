package adbs.util.manager;

import adbs.device.AdbDevice;
import adbs.exception.RemoteException;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PackageManager {

    private static final String SUCCESS = "Success";

    private static final String FAILURE = "Failure";

    private final AdbDevice device;

    private final FileManager fm;

    private final ShellManager shell;

    public PackageManager(AdbDevice device) {
        this.device = device;
        this.fm = new FileManager(device);
        this.shell = new ShellManager(device);
    }

    private static String[] expandArgs(String head, String tail, String... args) {
        String[] expandArgs = new String[args.length + 2];
        expandArgs[0] = head;
        System.arraycopy(args, 0, expandArgs, 1, args.length);
        expandArgs[args.length + 1] = tail;
        return expandArgs;
    }

    public List<String> list() {
        String result = shell.shell("pm", "list", "packages");
        List<String> packages = new ArrayList<>();
        for(String pkg : result.split("\r|\n")) {
            pkg = StringUtils.trim(pkg);
            if (StringUtils.isEmpty(pkg)) {
                continue;
            }
            packages.add(StringUtils.removeStart(pkg, "package:"));
        }
        return packages;
    }

    public void install(File apk, String... args) throws IOException {
        String remote = "/data/local/tmp/" + apk.getName();
        fm.push(apk, remote);
        String result = shell.shell("pm", expandArgs("install", remote, args));
        result = StringUtils.trim(result);
        fm.delete(remote);
        if (!result.startsWith(SUCCESS)) {
            throw new RemoteException(result);
        }
    }

    public void uninstall(String pkg, String... args) throws IOException {
        String result = shell.shell("pm", expandArgs("uninstall", pkg, args));
        result = StringUtils.trim(result);
        if (!result.startsWith(SUCCESS)) {
            throw new RemoteException(result);
        }
    }

    public void launch(String pkg) throws IOException {
        String result = shell.shell("monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1");
        result = StringUtils.trim(result);
        if (result.contains("elapsed time")) {
            throw new RemoteException(result);
        }
    }

    public void launch(String pkg, String activity) throws IOException {
        launch(pkg, activity, null);
    }

    public void launch(String pkg, String activity, String data, String... args) throws IOException {
        List<String> argList = new ArrayList<>();
        argList.add("start");
        argList.add("-n");
        argList.add(pkg + "/" + activity);
        if (data != null) {
            argList.add("-d");
            argList.add(data);
        }
        Collections.addAll(argList, args);
        String[] fullArgs = new String[argList.size()];
        argList.toArray(fullArgs);
        String result = shell.shell("am", fullArgs);
        result = StringUtils.trim(result);
        if (result.contains("Error type")) {
            throw new RemoteException(result);
        }
    }

    public void stop(String pkg) throws IOException {
        String result = shell.shell("am", "force-stop", pkg);
        result = StringUtils.trim(result);
        if (StringUtils.isNotEmpty(result)) {
            throw new RemoteException(result);
        }
    }
}
