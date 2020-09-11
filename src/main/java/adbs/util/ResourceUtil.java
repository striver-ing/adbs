package adbs.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class ResourceUtil {

    public static byte[] readAll(String name) throws IOException {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        InputStream is = classLoader.getResourceAsStream(name);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        byte[] bytes = new byte[8192];
        int size;
        while((size = is.read(bytes)) != -1) {
            os.write(bytes, 0, size);
        }
        os.close();
        return os.toByteArray();
    }
}
