package com.cosmetic.scm.domain;

/** 运输方式。客户自提时运输由客户负责，不要求记录途中温度。 */
public enum TransportMode {
    VEHICLE("专车（自有或对方车辆）"),
    LOGISTICS("物流 / 零担"),
    EXPRESS("快递"),
    COLD_CHAIN("冷链"),
    PICKUP("客户自提");

    private final String label;

    TransportMode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
