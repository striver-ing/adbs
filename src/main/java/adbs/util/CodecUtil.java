package adbs.util;

import adbs.channel.AdbChannel;
import adbs.constant.Command;
import adbs.entity.AdbPacket;
import io.netty.buffer.ByteBuf;

import java.util.List;

public class CodecUtil {

    private CodecUtil() {}

    public static void afterEncode(AdbChannel adbChannel, List<Object> out) {
        for(int i=0; i<out.size(); i++) {
            Object o = out.get(i);
            if (!(o instanceof ByteBuf)) {
                continue;
            }
            AdbPacket packet = new AdbPacket(Command.A_WRTE, adbChannel.localId(), adbChannel.remoteId(), (ByteBuf) o);
            out.set(i, packet);
        }
    }
}
