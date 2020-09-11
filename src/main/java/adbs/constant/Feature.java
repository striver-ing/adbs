package adbs.constant;

public enum Feature {

    //设备支持功能
    CMD("cmd"),
    STAT_V2("stat_v2"),
    SHELL_V2("shell_v2"),
    LS_V2("ls_v2"),
    LIBUSB("libusb"),
    PUSH_SYNC("push_sync"),
    APEX("apex"),
    FIXED_PUSH_MKDIR("fixed_push_mkdir"),
    ABB("abb"),
    FIXED_PUSH_SYMLINK_TIMESTAMP("fixed_push_symlink_timestamp"),
    ABB_EXEC("abb_exec"),
    REMOUNT_SHELL("remount_shell"),
    TRACK_APP("track_app"),
    SENDRECV_V2("sendrecv_v2"),
    SENDRECV_V2_BROTLI("sendrecv_v2_brotli"),
    SENDRECV_V2_LZ4("sendrecv_v2_lz4"),
    SENDRECV_V2_DRY_RUN_SEND("sendrecv_v2_dry_run_send"),

    ;

    private final String code;

    Feature(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static Feature findByCode(String code) {
        for(Feature state : values()) {
            if (state.getCode().equalsIgnoreCase(code)) {
                return state;
            }
        }
        return null;
    }
}
