package com.garment.factory.dto;

/**
 * 量体记录被订单引用的轻量投影：量体记录列表提示「使用中的订单及状态」、
 * 删除阻断说明阻断来源均基于此。
 */
public class MeasurementOrderRefDto {

    private final Long measurementId;
    private final Long orderId;
    private final String orderNo;
    private final String orderStatus;
    private final String customerName;

    public MeasurementOrderRefDto(Long measurementId, Long orderId, String orderNo, String orderStatus,
                                  String customerName) {
        this.measurementId = measurementId;
        this.orderId = orderId;
        this.orderNo = orderNo;
        this.orderStatus = orderStatus;
        this.customerName = customerName;
    }

    public Long getMeasurementId() {
        return measurementId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public String getOrderStatus() {
        return orderStatus;
    }

    public String getCustomerName() {
        return customerName;
    }
}
