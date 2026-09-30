package com.garment.factory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 量体记录列表行：由 JPQL 构造器表达式直接投影。
 * version 随列表下发，下单向导选中历史记录时回传，服务端据此识别并发编辑。
 */
public class MeasurementListItemDto {

    private final Long id;
    private final String recordNo;
    private final Long customerId;
    private final String customerName;
    private final String tailorName;
    private final LocalDate measureDate;
    private final BigDecimal bust;
    private final BigDecimal waist;
    private final BigDecimal hip;
    private final BigDecimal shoulder;
    private final BigDecimal sleeveLength;
    private final BigDecimal bodyLength;
    private final BigDecimal pantsLength;
    private final String remark;
    private final Integer version;

    /** 引用计数（列表查询后由 service 批量回填，非构造器投影字段） */
    private long activeOrderCount;
    private long deliveredOrderCount;

    public MeasurementListItemDto(Long id, String recordNo, Long customerId, String customerName, String tailorName,
                                  LocalDate measureDate, BigDecimal bust, BigDecimal waist, BigDecimal hip,
                                  BigDecimal shoulder, BigDecimal sleeveLength, BigDecimal bodyLength,
                                  BigDecimal pantsLength, String remark, Integer version) {
        this.id = id;
        this.recordNo = recordNo;
        this.customerId = customerId;
        this.customerName = customerName;
        this.tailorName = tailorName;
        this.measureDate = measureDate;
        this.bust = bust;
        this.waist = waist;
        this.hip = hip;
        this.shoulder = shoulder;
        this.sleeveLength = sleeveLength;
        this.bodyLength = bodyLength;
        this.pantsLength = pantsLength;
        this.remark = remark;
        this.version = version;
    }

    public Long getId() {
        return id;
    }

    public String getRecordNo() {
        return recordNo;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public String getCustomerName() {
        return customerName;
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

    public String getRemark() {
        return remark;
    }

    public Integer getVersion() {
        return version;
    }

    public long getActiveOrderCount() {
        return activeOrderCount;
    }

    public void setActiveOrderCount(long activeOrderCount) {
        this.activeOrderCount = activeOrderCount;
    }

    public long getDeliveredOrderCount() {
        return deliveredOrderCount;
    }

    public void setDeliveredOrderCount(long deliveredOrderCount) {
        this.deliveredOrderCount = deliveredOrderCount;
    }
}
