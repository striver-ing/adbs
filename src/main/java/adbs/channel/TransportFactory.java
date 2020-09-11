package adbs.channel;

public interface TransportFactory<I, O> {

    Transport<I, O> factory(AdbChannel channel);

}
