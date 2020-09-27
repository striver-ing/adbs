package adbs.feature;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public interface AdbHttp {

    FullHttpResponse execute(HttpRequest request, long timeout, TimeUnit unit) throws IOException;

}
