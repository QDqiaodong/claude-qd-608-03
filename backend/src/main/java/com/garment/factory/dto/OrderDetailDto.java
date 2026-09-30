package com.garment.factory.dto;

/**
 * 订单详情：订单主体 + 冻结量体口径（看板/工序侧同一来源）。
 */
public class OrderDetailDto {

    private com.garment.factory.domain.CustomOrder order;
    private OrderMeasurementView measurement;

    public OrderDetailDto(com.garment.factory.domain.CustomOrder order, OrderMeasurementView measurement) {
        this.order = order;
        this.measurement = measurement;
    }

    public com.garment.factory.domain.CustomOrder getOrder() {
        return order;
    }

    public void setOrder(com.garment.factory.domain.CustomOrder order) {
        this.order = order;
    }

    public OrderMeasurementView getMeasurement() {
        return measurement;
    }

    public void setMeasurement(OrderMeasurementView measurement) {
        this.measurement = measurement;
    }
}
