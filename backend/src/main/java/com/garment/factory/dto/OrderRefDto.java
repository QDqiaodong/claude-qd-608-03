package com.garment.factory.dto;

/**
 * 量体记录被哪些订单引用：量体记录编辑提示 / 删除拦截时回传，
 * 让操作人明确看到使用中的订单号与状态，以及删除为什么被服务端拒绝。
 */
public class OrderRefDto {

    private final Long orderId;
    private final String orderNo;
    private final String status;
    private final String statusName;
    /** true=未交付（删除会被拦截、编辑不会回写需提示）；false=仅历史已交付订单引用 */
    private final boolean active;

    public OrderRefDto(Long orderId, String orderNo, String status, String statusName, boolean active) {
        this.orderId = orderId;
        this.orderNo = orderNo;
        this.status = status;
        this.statusName = statusName;
        this.active = active;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public String getStatus() {
        return status;
    }

    public String getStatusName() {
        return statusName;
    }

    public boolean isActive() {
        return active;
    }
}
