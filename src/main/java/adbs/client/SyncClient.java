package adbs.client;

import adbs.channel.AbstractTransport;
import adbs.channel.AdbChannel;
import adbs.channel.Transport;
import adbs.channel.FutureTransportFactory;
import adbs.codec.AdbSyncDecoder;
import adbs.codec.AdbSyncEncoder;
import adbs.codec.AdbSyncListAggregator;
import adbs.constant.Feature;
import adbs.constant.SyncID;
import adbs.device.AdbDevice;
import adbs.entity.*;
import com.google.common.util.concurrent.SettableFuture;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;

import java.io.*;
import java.net.ProtocolException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static adbs.constant.Constants.DEFAULT_READ_TIMEOUT;
import static adbs.constant.Constants.SYNC_DATA_MAX;

public class SyncClient {

    private static final int DEFAULT_MODE = 0664;

    private final AdbDevice device;

    public SyncClient(AdbDevice device) {
        this.device = device;
    }

    private <T> T exec(Callable<Future<T>> callable) throws IOException {
        try {
            return callable.call().get(DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
        } catch (ExecutionException e0) {
            Throwable cause = e0.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;
            } else {
                throw new IOException(cause.getMessage(), cause);
            }
        } catch (Throwable e1) {
            throw new IOException(e1.getMessage(), e1);
        }
    }

    public SyncStat stat(String path) throws IOException {
        return exec(() -> {
            SettableFuture<SyncStat> future = SettableFuture.create();
            Transport<SyncStat, SyncPath> transport = device.open(
                    "sync:\0", DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS,
                    new FutureTransportFactory<SyncStat, SyncPath>() {
                        @Override
                        public Transport<SyncStat, SyncPath> factory(AdbChannel channel) {
                            return new SyncTransport<SyncStat, SyncPath>(channel) {
                                @Override
                                public void onMessage(SyncStat message) {
                                    future.set(message);
                                }

                                @Override
                                public void onError(Throwable cause) {
                                    future.setException(cause);
                                }
                            };
                        }
                    }
            );
            try {
                boolean hasStatV2 = device.features().contains(Feature.STAT_V2);
                SyncID sid = hasStatV2 ? SyncID.STAT_V2 : SyncID.LSTAT_V1;
                transport.write(new SyncPath(sid, path));
                return future;
            } finally {
                transport.close();
            }
        });
    }

    public SyncDent[] list(String path) throws IOException {
        return exec(() -> {
            SettableFuture<SyncDent[]> future = SettableFuture.create();
            Transport<SyncDent[], SyncPath> transport = device.open(
                    "sync:\0", DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS,
                    new FutureTransportFactory<SyncDent[], SyncPath>() {
                        @Override
                        public Transport<SyncDent[], SyncPath> factory(AdbChannel channel) {
                            return new SyncTransport<SyncDent[], SyncPath>(channel) {
                                @Override
                                public void onMessage(SyncDent[] message) {
                                    future.set(message);
                                }

                                @Override
                                public void onError(Throwable cause) {
                                    future.setException(cause);
                                }
                            };
                        }
                    }
            );
            try {
                boolean hasLsV2 = device.features().contains(Feature.LS_V2);
                SyncID sid = hasLsV2 ? SyncID.LIST_V2 : SyncID.LIST_V1;
                transport.write(new SyncPath(sid, path));
                return future;
            } finally {
                transport.close();
            }
        });
    }

    public int pull(String src, File dest) throws IOException {
        FileOutputStream os = new FileOutputStream(dest);
        try {
            return pull(src, os);
        } finally {
            os.flush();
            os.close();
        }
    }

    public int pull(String src, OutputStream dest) throws IOException {
        return exec(() -> {
            SettableFuture<Integer> future = SettableFuture.create();
            AtomicInteger counter = new AtomicInteger(0);
            Transport<SyncMessage, SyncPath> transport = device.open(
                    "sync:\0", DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS,
                    new FutureTransportFactory<SyncMessage, SyncPath>() {
                        @Override
                        public Transport<SyncMessage, SyncPath> factory(AdbChannel channel) {
                            return new SyncTransport<SyncMessage, SyncPath>(channel) {
                                @Override
                                public void onMessage(SyncMessage message) {
                                    if (message instanceof SyncData) {
                                        ByteBuf buf = ((SyncData) message).data;
                                        try {
                                            int size = buf.readableBytes();
                                            buf.readBytes(dest, size);
                                            counter.addAndGet(size);
                                        } catch (Throwable cause) {
                                            future.setException(cause);
                                        } finally {
                                            ReferenceCountUtil.safeRelease(message);
                                        }
                                    } else if (SyncID.DONE.equals(message.sid)) {
                                        future.set(counter.get());
                                    } else {
                                        onError(new ProtocolException("unexpected type: " + message));
                                    }
                                }

                                @Override
                                public void onError(Throwable cause) {
                                    future.setException(cause);
                                }
                            };
                        }
                    }
            );
            try {
                transport.write(new SyncPath(SyncID.RECV_V1, src));
                return future;
            } finally {
                transport.close();
            }
        });
    }

    public int push(File src, String dest) throws IOException {
        FileInputStream is = new FileInputStream(src);
        try {
            Long mtime = src.lastModified() / 1000;
            return push(is, dest, DEFAULT_MODE, mtime.intValue());
        } finally {
            is.close();
        }
    }

    public int push(InputStream src, String dest, int mode, int mtime) throws IOException {
        return exec(() -> {
            SettableFuture<Integer> future = SettableFuture.create();
            AtomicInteger counter = new AtomicInteger(0);
            Transport<SyncMessage, SyncMessage> transport = device.open(
                    "sync:\0", DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS,
                    new FutureTransportFactory<SyncMessage, SyncMessage>() {
                        @Override
                        public Transport<SyncMessage, SyncMessage> factory(AdbChannel channel) {
                            return new SyncTransport<SyncMessage, SyncMessage>(channel) {
                                @Override
                                public void onMessage(SyncMessage message) {
                                    if (SyncID.OKAY.equals(message.sid)) {
                                        future.set(counter.get());
                                    } else {
                                        onError(new ProtocolException("unexpected type: " + message));
                                    }
                                }

                                @Override
                                public void onError(Throwable cause) {
                                    future.setException(cause);
                                }
                            };
                        }
                    }
            );
            try {
                String destAndMode = dest + "," + mode;
                transport.write(new SyncPath(SyncID.SEND_V1, destAndMode));
                while (true) {
                    byte[] data = new byte[SYNC_DATA_MAX];
                    ByteBuf payload = null;
                    try {
                        int size = src.read(data);
                        if (size == -1) {
                            break;
                        }
                        if (size == 0) {
                            continue;
                        }
                        payload = Unpooled.wrappedBuffer(data, 0, size);
                        transport.write(new SyncData(payload));
                        counter.addAndGet(size);
                    } catch (Throwable cause) {
                        if (payload != null) {
                            ReferenceCountUtil.safeRelease(payload);
                        }
                        future.setException(cause);
                    }
                }
                transport.write(new SyncDone(mtime));
                return future;
            } finally {
                transport.close();
            }
        });
    }

    private abstract static class SyncTransport<I, O> extends AbstractTransport<I, O> {

        public SyncTransport(AdbChannel channel) {
            super(channel);
        }

        @Override
        protected void init() {
            channel.addHandler(new AdbSyncDecoder())
                    .addHandler(new AdbSyncEncoder(channel))
                    .addHandler(new AdbSyncListAggregator());
        }

    }
}
