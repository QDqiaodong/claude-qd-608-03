package com.garment.factory.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 量体记录列表行：由 JPQL 构造器表达式直接投影；
 * 被订单引用情况（使用中的订单、是否可删）由服务层回填。
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
    private final Long version;

    /** 引用该记录的未交付订单（服务层回填）；为空表示编辑/删除不会影响在产订单 */
    private List<MeasurementOrderRefDto> activeOrders = List.of();
    /** 引用该记录的已交付订单数（服务层回填）：这些订单只看自己的快照 */
    private long deliveredOrderCount = 0L;

    public MeasurementListItemDto(Long id, String recordNo, Long customerId, String customerName, String tailorName,
                                  LocalDate measureDate, BigDecimal bust, BigDecimal waist, BigDecimal hip,
                                  BigDecimal shoulder, BigDecimal sleeveLength, BigDecimal bodyLength,
                                  BigDecimal pantsLength, String remark, Long version) {
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

    public Long getVersion() {
        return version;
    }

    public List<MeasurementOrderRefDto> getActiveOrders() {
        return activeOrders;
    }

    public void setActiveOrders(List<MeasurementOrderRefDto> activeOrders) {
        this.activeOrders = activeOrders == null ? List.of() : activeOrders;
    }

    public long getDeliveredOrderCount() {
        return deliveredOrderCount;
    }

    public void setDeliveredOrderCount(long deliveredOrderCount) {
        this.deliveredOrderCount = deliveredOrderCount;
    }
}
