package adbs.channel;

import java.net.SocketAddress;

public class AdbChannelAddress extends SocketAddress {

    private final String destination;

    private final int id;

    public AdbChannelAddress(String destination, int id) {
        this.destination = destination;
        this.id = id;
    }

    public String destination() {
        return destination;
    }

    public int id() {
        return id;
    }

    @Override
    public String toString() {
        return id + ":" + destination;
    }
}
