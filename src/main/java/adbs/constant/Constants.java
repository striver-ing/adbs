package adbs.constant;

public class Constants {

    private Constants() {}

    public static final int A_VERSION = 0x01000001;

    public static final int A_VERSION_MIN = 0x01000000;

    public static final int A_VERSION_SKIP_CHECKSUM = 0x01000001;

    public static final int MAX_PAYLOAD = 1024 * 1024;

    public static final int ADB_AUTH_TOKEN = 1;

    public static final int ADB_AUTH_SIGNATURE = 2;

    public static final int ADB_AUTH_RSAPUBLICKEY = 3;

    public static final int TOKEN_SIZE = 20;

    public static final byte[] ASN1_PREAMBLE = new byte[]{0x00, 0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2B, 0x0E, 0x03, 0x02, 0x1A, 0x05, 0x00, 0x04, 0x14};

    public static final int ADB_PROTOCOL_HEADER_SIZE = 24;

    public static final int DEFAULT_READ_TIMEOUT = 30;

    public static final int SYNC_DATA_MAX = 64 * 1024;

    public static final int WRITE_DATA_MAX = 256 * 1024;

    public static final int S_IFMT = 00170000;
    public static final int S_IRWXU = 00700;
    public static final int S_IRUSR = 00400;
    public static final int S_IWUSR = 00200;
    public static final int S_IXUSR = 00100;
    public static final int S_IRWXG = 00070;
    public static final int S_IRGRP = 00040;
    public static final int S_IWGRP = 00020;
    public static final int S_IXGRP = 00010;
    public static final int S_IRWXO = 00007;
    public static final int S_IROTH = 00004;
    public static final int S_IWOTH = 00002;
    public static final int S_IXOTH = 00001;

}
