package adbs.feature;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public interface AdbHttp {

    FullHttpResponse execute(FullHttpRequest request, long timeout, TimeUnit unit) throws IOException;

}
