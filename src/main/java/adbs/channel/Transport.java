package adbs.channel;

import io.netty.util.concurrent.Future;

public interface Transport<I, O> {

    void onOpen();

    void onMessage(I message);

    void onClose();

    void onError(Throwable cause);

    Future<?> write(O request);

    Future<?> close();

}
