package adbs.channel;

import adbs.constant.Constants;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

public abstract class AdbFileChannel implements WritableByteChannel {

    private volatile boolean open;

    public AdbFileChannel() {
        this.open = true;
    }

    protected abstract void write(ByteBuf buf);

    @Override
    public final int write(ByteBuffer src) throws IOException {
        int total = 0;
        int size;
        ByteBuf buffer = Unpooled.wrappedBuffer(src);
        try {
            while ((size = buffer.readableBytes()) > 0) {
                size = Math.min(size, Constants.MAX_PAYLOAD);
                ByteBuf payload = buffer.readRetainedSlice(size);
                total += size;
                try {
                    write(payload);
                } catch (Throwable cause) {
                    ReferenceCountUtil.safeRelease(payload);
                    throw cause;
                }
            }
        } finally {
            ReferenceCountUtil.safeRelease(buffer);
        }
        return total;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() throws IOException {
        this.open = false;
    }
}
