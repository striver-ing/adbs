package adbs.util;

import io.netty.buffer.ByteBuf;

public class MessageUtil {

    private MessageUtil() {}

    public static int checksum(ByteBuf payload) {
        int sum = 0;
        for(int i=0; i<payload.readableBytes(); i++) {
            sum += (payload.getUnsignedByte(i));
        }
        return sum;
    }
}
