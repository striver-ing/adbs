package adbs.client;

import adbs.channel.AbstractTransport;
import adbs.channel.AdbChannel;
import adbs.channel.Transport;
import adbs.channel.FutureTransportFactory;
import adbs.constant.Constants;
import adbs.device.AdbDevice;
import adbs.util.CodecUtil;
import com.google.common.util.concurrent.SettableFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class HttpClient {
    
    private final AdbDevice device;
    
    private final int port;

    public HttpClient(AdbDevice device, int port) {
        this.device = device;
        this.port = port;
    }
    
    public FullHttpResponse execute(FullHttpRequest request, long timeout, TimeUnit unit) throws IOException {
        SettableFuture<FullHttpResponse> future = SettableFuture.create();
        Transport<FullHttpResponse, FullHttpRequest> transport = device.open(
                "tcp:" + port + "\0", Constants.DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS,
                new FutureTransportFactory<FullHttpResponse, FullHttpRequest>() {
                    @Override
                    public Transport<FullHttpResponse, FullHttpRequest> factory(AdbChannel channel) {
                        return new AbstractTransport<FullHttpResponse, FullHttpRequest>(channel) {
                            @Override
                            protected void init() {
                                channel.addHandler(new HttpResponseDecoder())
                                        .addHandler(new HttpObjectAggregator(8 * 1024 * 1024))
                                        .addHandler(new HttpRequestEncoder(){
                                            @Override
                                            protected void encode(ChannelHandlerContext ctx, Object msg, List<Object> out) throws Exception {
                                                super.encode(ctx, msg, out);
                                                CodecUtil.afterEncode(channel, out);
                                            }
                                        });
                            }

                            @Override
                            public void onOpen() {
                                write(request);
                            }

                            @Override
                            public void onMessage(FullHttpResponse message) {
                                future.set(message);
                            }
                        };
                    }
                }
        );
        try {
            return future.get(timeout, unit);
        } catch (Throwable cause) {
            throw new IOException("request failed: " + cause.getMessage(), cause);
        } finally {
            transport.close();
        }
    }
}
