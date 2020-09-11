package adbs.constant;

public enum DeviceType {

    //设备连接类型
    UNKNOWN("unknown"),
    DEVICE("device"),
    RECOVERY("recovery"),
    BOOTLOADER("bootloader"),
    SIDELOAD("sideload"),
    RESCUE("rescue")

    ;

    private final String code;

    DeviceType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static DeviceType findByCode(String code) {
        for(DeviceType state : values()) {
            if (state.getCode().equalsIgnoreCase(code)) {
                return state;
            }
        }
        return UNKNOWN;
    }
}
