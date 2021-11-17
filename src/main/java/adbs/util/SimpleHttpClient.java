package adbs.util;

import adbs.device.AdbDevice;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.Promise;

import java.nio.channels.ClosedChannelException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class SimpleHttpClient {

    private static final int DEFAULT_TIMEOUT = 30000;

    private final AdbDevice device;

    private final int port;

    public SimpleHttpClient(AdbDevice device, int port) {
        this.device = device;
        this.port = port;
    }

    public SimpleHttpResponse execute(HttpMethod method, String uri, HttpHeaders headers, byte[] body, int timeoutMs) throws Exception {
//        Promise<SimpleHttpResponse> promise = device.eventLoop().newPromise();
//        ChannelFuture future = device.open(
//                "tcp:" + port + "\0",
//                timeoutMs,
//                channel -> {
//                    channel.pipeline()
//                            .addLast(new HttpClientCodec())
//                            .addLast(new HttpObjectAggregator(8 * 1024 * 1024))
//                            .addLast(new ChannelInboundHandlerAdapter(){
//
//                                @Override
//                                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
//                                    if (msg instanceof FullHttpResponse) {
//                                        try {
//                                            FullHttpResponse response = (FullHttpResponse) msg;
//                                            byte[] responseBody;
//                                            if (response.content() != null) {
//                                                responseBody = new byte[response.content().readableBytes()];
//                                                response.content().readBytes(responseBody);
//                                            } else {
//                                                responseBody = null;
//                                            }
//                                            SimpleHttpResponse resp = new SimpleHttpResponse(
//                                                    response.protocolVersion(),
//                                                    response.status(),
//                                                    response.headers(),
//                                                    responseBody
//                                            );
//                                            promise.trySuccess(resp);
//                                        } catch (Throwable cause) {
//                                            promise.tryFailure(cause);
//                                        } finally {
//                                            ReferenceCountUtil.safeRelease(msg);
//                                        }
//                                    } else {
//                                        ctx.fireChannelRead(msg);
//                                    }
//                                }
//
//                                @Override
//                                public void channelInactive(ChannelHandlerContext ctx) throws Exception {
//                                    promise.tryFailure(new ClosedChannelException());
//                                }
//                            });
//        });
//        Channel channel = future.get(timeoutMs, TimeUnit.MILLISECONDS);
//        HttpRequest request;
//        if (body == null) {
//            request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, uri, headers);
//        } else {
//            request = new DefaultFullHttpRequest(
//                    HttpVersion.HTTP_1_1,
//                    method,
//                    uri,
//                    Unpooled.wrappedBuffer(body),
//                    headers,
//                    headers
//            );
//        }
//        try {
//            channel.writeAndFlush(request).get(timeoutMs, TimeUnit.MILLISECONDS);
//        } catch (Throwable cause) {
//            channel.close().get(timeoutMs, TimeUnit.MILLISECONDS);
//            throw cause;
//        } finally {
//            if (request instanceof FullHttpRequest && ((FullHttpRequest) request).refCnt() > 0) {
//                ReferenceCountUtil.safeRelease(request);
//            }
//        }
//        try {
//            return promise.get(timeoutMs, TimeUnit.MILLISECONDS);
//        } finally {
//            channel.close().get(timeoutMs, TimeUnit.MILLISECONDS);
//        }
        return null;
    }

    public SimpleHttpResponse get(String uri) throws Exception {
        HttpHeaders headers = new DefaultHttpHeaders();
        return execute(HttpMethod.GET, uri, headers, null, DEFAULT_TIMEOUT);
    }

    public SimpleHttpResponse post(String uri, String contentType, byte[] body) throws Exception {
        HttpHeaders headers = new DefaultHttpHeaders();
        headers.set(HttpHeaderNames.CONTENT_TYPE, contentType);
        headers.set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        return execute(HttpMethod.POST, uri, headers, body, DEFAULT_TIMEOUT);
    }

    public SimpleHttpResponse post(String uri, String body) throws Exception {
        return post(uri, HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED.toString(), body.getBytes(StandardCharsets.UTF_8));
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
