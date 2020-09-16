package adbs.feature;

import adbs.entity.sync.SyncDent;
import adbs.entity.sync.SyncStat;

import java.io.*;

public interface AdbSync {

    int DEFAULT_MODE = 0664;

    SyncStat stat(String path) throws IOException;

    SyncDent[] list(String path) throws IOException;

    int pull(String src, OutputStream dest) throws IOException;

    int push(InputStream src, String dest, int mode, int mtime) throws IOException;

    default int pull(String src, File dest) throws IOException {
        FileOutputStream os = new FileOutputStream(dest);
        try {
            return pull(src, os);
        } finally {
            os.flush();
            os.close();
        }
    }

    default int push(File src, String dest) throws IOException {
        FileInputStream is = new FileInputStream(src);
        try {
            Long mtime = src.lastModified() / 1000;
            return push(is, dest, DEFAULT_MODE, mtime.intValue());
        } finally {
            is.close();
        }
    }
}
