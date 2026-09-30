package com.garment.factory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 订单进度看板卡片：列表投影用；工序完成情况在 service 里另行汇总填充。
 * 量体口径（量体师/三围/待核对状态）来自 order_measurement_snapshot。
 */
public class OrderBoardCardDto {

    private final Long id;
    private final String orderNo;
    private final String customerName;
    private final String styleName;
    private final String fabricName;
    private final String colorHex;
    private final LocalDate deliveryDate;
    private final BigDecimal totalPrice;
    private final BigDecimal usageMeters;
    private final String status;
    private final Boolean balancePaid;

    /** 冻结量体快照（看板抽屉直接展示同一口径；旧订单未补齐时由 service 惰性回填） */
    private OrderMeasurementSnapshotDto measurementSnapshot;

    /** 工单总数（服务层回填） */
    private long workOrderTotal;
    /** 已完成工单数（服务层回填） */
    private long workOrderDone;

    /** 试衣整改汇总（服务层回填：未关闭数/阻断数/返修轮次/当前阶段） */
    private Map<String, Object> fitting;

    public OrderBoardCardDto(Long id, String orderNo, String customerName, String styleName, String fabricName,
                             String colorHex, LocalDate deliveryDate, BigDecimal totalPrice,
                             BigDecimal usageMeters, String status, Boolean balancePaid,
                             // ---- 快照列（LEFT JOIN，可能全为 null） ----
                             String snapRecordNo, String snapTailorName, LocalDate snapMeasureDate,
                             BigDecimal snapBust, BigDecimal snapWaist, BigDecimal snapHip,
                             BigDecimal snapShoulder, BigDecimal snapSleeveLength, BigDecimal snapBodyLength,
                             BigDecimal snapPantsLength, String snapMeasureRemark, String snapStatus,
                             String snapVerifyNote, String snapSource, LocalDateTime snapFrozenAt,
                             String snapConfirmedBy, Long measurementId) {
        this.id = id;
        this.orderNo = orderNo;
        this.customerName = customerName;
        this.styleName = styleName;
        this.fabricName = fabricName;
        this.colorHex = colorHex;
        this.deliveryDate = deliveryDate;
        this.totalPrice = totalPrice;
        this.usageMeters = usageMeters;
        this.status = status;
        this.balancePaid = balancePaid;
        if (snapStatus != null) {
            this.measurementSnapshot = new OrderMeasurementSnapshotDto(measurementId, snapRecordNo, snapTailorName,
                    snapMeasureDate, snapBust, snapWaist, snapHip, snapShoulder, snapSleeveLength, snapBodyLength,
                    snapPantsLength, snapMeasureRemark, snapStatus, snapVerifyNote, snapSource, snapFrozenAt,
                    snapConfirmedBy);
        }
    }

    public Long getId() {
        return id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getStyleName() {
        return styleName;
    }

    public String getFabricName() {
        return fabricName;
    }

    public String getColorHex() {
        return colorHex;
    }

    /** 看板旧模板仍直接取 card.tailorName，代理到冻结快照；待核对/无快照返回 null */
    public String getTailorName() {
        return measurementSnapshot == null ? null : measurementSnapshot.getTailorName();
    }

    public LocalDate getDeliveryDate() {
        return deliveryDate;
    }

    public BigDecimal getTotalPrice() {
        return totalPrice;
    }

    public BigDecimal getUsageMeters() {
        return usageMeters;
    }

    public String getStatus() {
        return status;
    }

    public Boolean getBalancePaid() {
        return balancePaid;
    }

    public OrderMeasurementSnapshotDto getMeasurementSnapshot() {
        return measurementSnapshot;
    }

    public void setMeasurementSnapshot(OrderMeasurementSnapshotDto measurementSnapshot) {
        this.measurementSnapshot = measurementSnapshot;
    }

    public long getWorkOrderTotal() {
        return workOrderTotal;
    }

    public void setWorkOrderTotal(long workOrderTotal) {
        this.workOrderTotal = workOrderTotal;
    }

    public long getWorkOrderDone() {
        return workOrderDone;
    }

    public void setWorkOrderDone(long workOrderDone) {
        this.workOrderDone = workOrderDone;
    }

    public Map<String, Object> getFitting() {
        return fitting;
    }

    public void setFitting(Map<String, Object> fitting) {
        this.fitting = fitting;
    }
}
