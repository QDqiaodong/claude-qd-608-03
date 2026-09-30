package com.garment.factory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 订单量体快照视图：订单看板 / 订单列表 / 订单详情 / 工序侧统一使用本对象，
 * 数据来源固定为 order_measurement_snapshot，禁止回退读取可编辑的量体记录表。
 *
 * pendingVerify=true 表示旧订单的原量体记录已缺失、尺寸待人工核对，
 * 此时各部位尺寸为 null，任何页面都不得拿实时量体记录顶替展示。
 */
public class OrderMeasurementSnapshotDto {

    private final Long measurementId;
    private final String recordNo;
    private final String tailorName;
    private final LocalDate measureDate;
    private final BigDecimal bust;
    private final BigDecimal waist;
    private final BigDecimal hip;
    private final BigDecimal shoulder;
    private final BigDecimal sleeveLength;
    private final BigDecimal bodyLength;
    private final BigDecimal pantsLength;
    private final String measureRemark;
    /** FROZEN / PENDING_VERIFY / MANUAL_CONFIRMED */
    private final String status;
    private final boolean pendingVerify;
    private final String verifyNote;
    private final String frozenSource;
    private final LocalDateTime frozenAt;
    private final String confirmedBy;

    public OrderMeasurementSnapshotDto(Long measurementId, String recordNo, String tailorName, LocalDate measureDate,
                                       BigDecimal bust, BigDecimal waist, BigDecimal hip, BigDecimal shoulder,
                                       BigDecimal sleeveLength, BigDecimal bodyLength, BigDecimal pantsLength,
                                       String measureRemark, String status, String verifyNote, String frozenSource,
                                       LocalDateTime frozenAt, String confirmedBy) {
        this.measurementId = measurementId;
        this.recordNo = recordNo;
        this.tailorName = tailorName;
        this.measureDate = measureDate;
        this.bust = bust;
        this.waist = waist;
        this.hip = hip;
        this.shoulder = shoulder;
        this.sleeveLength = sleeveLength;
        this.bodyLength = bodyLength;
        this.pantsLength = pantsLength;
        this.measureRemark = measureRemark;
        this.status = status;
        this.pendingVerify = com.garment.factory.domain.OrderMeasurementSnapshot.STATUS_PENDING_VERIFY.equals(status);
        this.verifyNote = verifyNote;
        this.frozenSource = frozenSource;
        this.frozenAt = frozenAt;
        this.confirmedBy = confirmedBy;
    }

    public Long getMeasurementId() {
        return measurementId;
    }

    public String getRecordNo() {
        return recordNo;
    }

    public String getTailorName() {
        return tailorName;
    }

    public LocalDate getMeasureDate() {
        return measureDate;
    }

    public BigDecimal getBust() {
        return bust;
    }

    public BigDecimal getWaist() {
        return waist;
    }

    public BigDecimal getHip() {
        return hip;
    }

    public BigDecimal getShoulder() {
        return shoulder;
    }

    public BigDecimal getSleeveLength() {
        return sleeveLength;
    }

    public BigDecimal getBodyLength() {
        return bodyLength;
    }

    public BigDecimal getPantsLength() {
        return pantsLength;
    }

    public String getMeasureRemark() {
        return measureRemark;
    }

    public String getStatus() {
        return status;
    }

    public boolean isPendingVerify() {
        return pendingVerify;
    }

    public String getVerifyNote() {
        return verifyNote;
    }

    public String getFrozenSource() {
        return frozenSource;
    }

    public LocalDateTime getFrozenAt() {
        return frozenAt;
    }

    public String getConfirmedBy() {
        return confirmedBy;
    }
}
