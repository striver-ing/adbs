package adbs.entity;

import adbs.constant.DeviceType;
import adbs.constant.Feature;

import java.util.Set;

public class DeviceInfo {

    public final DeviceType type;

    public final String model;

    public final String product;

    public final String device;

    public final Set<Feature> features;

    public DeviceInfo(DeviceType type, String model, String product, String device, Set<Feature> features) {
        this.type = type;
        this.model = model;
        this.product = product;
        this.device = device;
        this.features = features;
    }
}
