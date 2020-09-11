package adbs.channel;


import com.google.common.util.concurrent.SettableFuture;

public abstract class FutureTransportFactory<I, O> implements TransportFactory<I, O> {

    private final SettableFuture<Transport<I, O>> future;

    public FutureTransportFactory() {
        this.future = SettableFuture.create();
    }

    public SettableFuture<Transport<I, O>> future() {
        return future;
    }

}
