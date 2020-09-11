package adbs.constant;

public enum Command {

    A_SYNC(0x434e5953),
    A_CNXN(0x4e584e43),
    A_AUTH(0x48545541),
    A_OPEN(0x4e45504f),
    A_OKAY(0x59414b4f),
    A_CLSE(0x45534c43),
    A_WRTE(0x45545257),
    A_STLS(0x534C5453),

    ;

    private final int value;

    private final int magic;

    Command(int value) {
        this.value = value;
        this.magic = value ^ 0xffffffff;
    }

    public int value() {
        return value;
    }

    public int magic() {
        return magic;
    }

    public static Command findByValue(int value) {
        for(Command command : values()) {
            if (command.value == value) {
                return command;
            }
        }
        return null;
    }
}
