package adbs.constant;

public enum FileType {

    //套接口文件（socket）
    SOCK(0140000, 's'),

    //符号链接文件（symbolic link）
    LNK(0120000, 'l'),

    //普通文件（regular file）
    REG(0100000, '-'),

    //块设备（block device）
    BLK(0060000, 'b'),

    //目录（directory）
    DIR(0040000, 'd'),

    //字符设备（character device）
    CHR(0020000, 'c'),

    //管道（FIFO<pipe>）
    FIFO(0010000, 'p')

    ;

    private int value;

    private char code;

    FileType(int value, char code) {
        this.value = value;
        this.code = code;
    }

    public int value() {
        return value;
    }

    public char code() {
        return code;
    }

    public static FileType findByValue(int mode) {
        int v = mode & Constants.S_IFMT;
        for(FileType type : values()) {
            if (type.value == v) {
                return type;
            }
        }
        return null;
    }

}
