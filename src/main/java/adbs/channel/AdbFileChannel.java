package adbs.channel;

import adbs.constant.Constants;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.ReferenceCountUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

public abstract class AdbFileChannel implements WritableByteChannel {

    private final ByteBufAllocator allocator;

    private volatile boolean open;

    public AdbFileChannel(ByteBufAllocator allocator) {
        this.allocator = allocator;
        this.open = true;
    }

    protected abstract void write(ByteBuf buf);

    @Override
    public final int write(ByteBuffer src) throws IOException {
        int total = 0;
        int size;
        while ((size = src.remaining()) > 0) {
            size = Math.min(size, Constants.MAX_PAYLOAD);
            total += size;
            ByteBuf buf = allocator.buffer(size, size);
            boolean success = false;
            try {
                byte[] bytes = new byte[size];
                src.get(bytes);
                buf.writeBytes(bytes);
                write(buf);
                success = true;
            } finally {
                if (!success) {
                    ReferenceCountUtil.safeRelease(buf);
                }
            }
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
