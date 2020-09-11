package adbs.entity;

import adbs.constant.DeviceType;
import adbs.constant.Feature;
import io.netty.channel.Channel;
import io.netty.channel.nio.NioEventLoopGroup;

import java.util.Set;

public class ConnectResult {

    private NioEventLoopGroup executors;

    private Channel channel;

    private DeviceType type;

    private String model;

    private String product;

    private String device;

    private Set<Feature> features;

    public NioEventLoopGroup getExecutors() {
        return executors;
    }

    public void setExecutors(NioEventLoopGroup executors) {
        this.executors = executors;
    }

    public Channel getChannel() {
        return channel;
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
    }

    public DeviceType getType() {
        return type;
    }

    public void setType(DeviceType type) {
        this.type = type;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getProduct() {
        return product;
    }

    public void setProduct(String product) {
        this.product = product;
    }

    public String getDevice() {
        return device;
    }

    public void setDevice(String device) {
        this.device = device;
    }

    public Set<Feature> getFeatures() {
        return features;
    }

    public void setFeatures(Set<Feature> features) {
        this.features = features;
    }
}
