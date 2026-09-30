package com.garment.factory.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * 订单量体快照：订单确认那一刻【冻结】的完整量体事实。
 *
 * <p>快照与订单 1:1，建立后不再随量体记录的编辑/删除而变化；看板、订单详情、
 * 工序侧一律读本表，杜绝「历史尺寸漂移」。</p>
 *
 * <p>status 口径：</p>
 * <ul>
 *   <li>{@link #STATUS_FROZEN}：下单确认时（或旧数据补齐时）从量体记录完整冻结；</li>
 *   <li>{@link #STATUS_MISSING}：旧订单的量体记录已缺失，无法补齐，尺寸列全部留空，等待人工核对；</li>
 *   <li>{@link #STATUS_MANUAL_CONFIRMED}：缺失记录的旧订单经人工核对后补录确认，补齐流程永不覆盖本行。</li>
 * </ul>
 */
@Entity
@Table(name = "order_measurement_snapshot")
public class OrderMeasurementSnapshot {

    public static final String STATUS_FROZEN = "FROZEN";
    public static final String STATUS_MISSING = "MISSING";
    public static final String STATUS_MANUAL_CONFIRMED = "MANUAL_CONFIRMED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属订单（1:1，唯一索引兜底并发补齐） */
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** 当时采用的量体记录来源（记录事后删除后保留来源指向）；人工补录时可为空 */
    @Column(name = "measurement_id")
    private Long measurementId;

    @Column(name = "record_no", length = 30)
    private String recordNo;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "customer_name", length = 50)
    private String customerName;

    @Column(name = "tailor_name", length = 50)
    private String tailorName;

    @Column(name = "measure_date")
    private LocalDate measureDate;

    /* MISSING 待核对状态下，以下七个尺寸列允许为空，绝不伪造 */
    @Column(name = "bust")
    private BigDecimal bust;

    @Column(name = "waist")
    private BigDecimal waist;

    @Column(name = "hip")
    private BigDecimal hip;

    @Column(name = "shoulder")
    private BigDecimal shoulder;

    @Column(name = "sleeve_len")
    private BigDecimal sleeveLength;

    @Column(name = "body_len")
    private BigDecimal bodyLength;

    @Column(name = "pants_len")
    private BigDecimal pantsLength;

    @Column(name = "remark", length = 500)
    private String remark;

    /** FROZEN / MISSING / MANUAL_CONFIRMED */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 自动补齐或下单冻结时的服务端时间；人工确认时为确认时间 */
    @Column(name = "frozen_at", nullable = false)
    private LocalDateTime frozenAt;

    /** 人工核对补录的操作人；FROZEN/MISSING 行为空 */
    @Column(name = "confirmed_by", length = 50)
    private String confirmedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.frozenAt == null) {
            this.frozenAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getMeasurementId() {
        return measurementId;
    }

    public void setMeasurementId(Long measurementId) {
        this.measurementId = measurementId;
    }

    public String getRecordNo() {
        return recordNo;
    }

    public void setRecordNo(String recordNo) {
        this.recordNo = recordNo;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getTailorName() {
        return tailorName;
    }

    public void setTailorName(String tailorName) {
        this.tailorName = tailorName;
    }

    public LocalDate getMeasureDate() {
        return measureDate;
    }

    public void setMeasureDate(LocalDate measureDate) {
        this.measureDate = measureDate;
    }

    public BigDecimal getBust() {
        return bust;
    }

    public void setBust(BigDecimal bust) {
        this.bust = bust;
    }

    public BigDecimal getWaist() {
        return waist;
    }

    public void setWaist(BigDecimal waist) {
        this.waist = waist;
    }

    public BigDecimal getHip() {
        return hip;
    }

    public void setHip(BigDecimal hip) {
        this.hip = hip;
    }

    public BigDecimal getShoulder() {
        return shoulder;
    }

    public void setShoulder(BigDecimal shoulder) {
        this.shoulder = shoulder;
    }

    public BigDecimal getSleeveLength() {
        return sleeveLength;
    }

    public void setSleeveLength(BigDecimal sleeveLength) {
        this.sleeveLength = sleeveLength;
    }

    public BigDecimal getBodyLength() {
        return bodyLength;
    }

    public void setBodyLength(BigDecimal bodyLength) {
        this.bodyLength = bodyLength;
    }

    public BigDecimal getPantsLength() {
        return pantsLength;
    }

    public void setPantsLength(BigDecimal pantsLength) {
        this.pantsLength = pantsLength;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getFrozenAt() {
        return frozenAt;
    }

    public void setFrozenAt(LocalDateTime frozenAt) {
        this.frozenAt = frozenAt;
    }

    public String getConfirmedBy() {
        return confirmedBy;
    }

    public void setConfirmedBy(String confirmedBy) {
        this.confirmedBy = confirmedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
