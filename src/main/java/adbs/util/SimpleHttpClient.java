package adbs.util;

import adbs.device.AdbDevice;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.Promise;

import java.nio.channels.ClosedChannelException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class SimpleHttpClient {

    private static final byte[] EMPTY_BODY = new byte[0];

    private final AdbDevice device;

    private final ChannelFuture future;

    private volatile Promise<SimpleHttpResponse> promise;

    public SimpleHttpClient(AdbDevice device, int port, int timeoutMs) {
        this.device = device;
        this.future = device.open(
                "tcp:" + port + "\0",
                timeoutMs,
                channel -> {
                    channel.pipeline()
                            .addLast(new HttpClientCodec())
                            .addLast(new HttpObjectAggregator(8 * 1024 * 1024))
                            .addLast(new ChannelInboundHandlerAdapter(){

                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                    if (msg instanceof FullHttpResponse) {
                                        try {
                                            FullHttpResponse response = (FullHttpResponse) msg;
                                            byte[] responseBody;
                                            if (response.content() != null) {
                                                responseBody = new byte[response.content().readableBytes()];
                                                response.content().readBytes(responseBody);
                                            } else {
                                                responseBody = null;
                                            }
                                            SimpleHttpResponse resp = new SimpleHttpResponse(
                                                    response.protocolVersion(),
                                                    response.status(),
                                                    response.headers(),
                                                    responseBody
                                            );
                                            promise.trySuccess(resp);
                                        } catch (Throwable cause) {
                                            promise.tryFailure(cause);
                                        } finally {
                                            ReferenceCountUtil.safeRelease(msg);
                                        }
                                    } else {
                                        ctx.fireChannelRead(msg);
                                    }
                                }

                                @Override
                                public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                                    promise.tryFailure(new ClosedChannelException());
                                }
                            });
                });
    }

    private synchronized SimpleHttpResponse execute(HttpMethod method, String uri, HttpHeaders headers, byte[] body) throws Exception {
        promise = device.eventLoop().newPromise();
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                method,
                uri,
                Unpooled.wrappedBuffer(body),
                headers,
                headers
        );
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        try {
            future.channel().writeAndFlush(request).get(30, TimeUnit.SECONDS);
            return promise.get(30, TimeUnit.SECONDS);
        } finally {
            if (request instanceof ReferenceCounted && ((ReferenceCounted) request).refCnt() > 0) {
                ReferenceCountUtil.safeRelease(request);
            }
        }
    }

    public SimpleHttpResponse get(String uri) throws Exception {
        HttpHeaders headers = new DefaultHttpHeaders();
        headers.set(HttpHeaderNames.CONTENT_LENGTH, EMPTY_BODY.length);
        return execute(HttpMethod.GET, uri, headers, EMPTY_BODY);
    }

    public SimpleHttpResponse post(String uri, String contentType, byte[] body) throws Exception {
        HttpHeaders headers = new DefaultHttpHeaders();
        headers.set(HttpHeaderNames.CONTENT_TYPE, contentType);
        headers.set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        return execute(HttpMethod.POST, uri, headers, body);
    }

    public SimpleHttpResponse post(String uri, String body) throws Exception {
        return post(uri, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED.toString(), body.getBytes(StandardCharsets.UTF_8));
    }

    public void close() {
        try {
            future.channel().close().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("close failed", e);
        }
    }

    public static class SimpleHttpResponse {

        private final HttpVersion protocolVersion;

        private final HttpResponseStatus status;

        private final HttpHeaders headers;

        private final byte[] body;

        public SimpleHttpResponse(HttpVersion protocolVersion, HttpResponseStatus status, HttpHeaders headers, byte[] body) {
            this.protocolVersion = protocolVersion;
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        public HttpVersion protocolVersion() {
            return protocolVersion;
        }

        public HttpResponseStatus status() {
            return status;
        }

        public HttpHeaders headers() {
            return headers;
        }

        public byte[] body() {
            return body;
        }

        public String bodyAsString(Charset charset) {
            if (body == null) {
                return null;
            }
            return new String(body, charset);
        }

        public String bodyAsString() {
            return bodyAsString(StandardCharsets.UTF_8);
        }
    }
}
