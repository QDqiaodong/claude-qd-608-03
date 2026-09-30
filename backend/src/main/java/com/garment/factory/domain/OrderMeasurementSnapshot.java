package com.garment.factory.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 订单量体快照：订单确认（建立）那一刻冻结下来的完整尺寸事实。
 *
 * 冻结后与量体记录(measurement)解耦：客户后续新建/修改/删除量体记录都不改变本快照，
 * 订单看板、订单详情、工序侧一律读取本表，保证三处口径一致、刷新不回退实时值。
 *
 * status：
 *   FROZEN           —— 下单时或旧数据补齐时从原量体记录完整冻结，可直接用于生产
 *   PENDING_VERIFY   —— 旧订单补齐时原量体记录已缺失，尺寸无法考证，严禁伪造，挂待核对
 *   MANUAL_CONFIRMED —— 待核对快照经人工核对后确认补齐，后续自动补齐不得再覆盖
 */
@Entity
@Table(name = "order_measurement_snapshot")
public class OrderMeasurementSnapshot {

    public static final String STATUS_FROZEN = "FROZEN";
    public static final String STATUS_PENDING_VERIFY = "PENDING_VERIFY";
    public static final String STATUS_MANUAL_CONFIRMED = "MANUAL_CONFIRMED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** 来源量体记录 id；旧订单记录缺失的待核对快照为 null */
    @Column(name = "measurement_id")
    private Long measurementId;

    /** 冻结当时的量体单号（即使来源记录后来被删除仍可追溯） */
    @Column(name = "record_no", length = 30)
    private String recordNo;

    @Column(name = "customer_name", length = 50)
    private String customerName;

    @Column(name = "tailor_name", length = 50)
    private String tailorName;

    @Column(name = "measure_date")
    private LocalDate measureDate;

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

    @Column(name = "measure_remark", length = 500)
    private String measureRemark;

    /** FROZEN / PENDING_VERIFY / MANUAL_CONFIRMED */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** FROZEN：下单冻结；BACKFILL：旧订单自动补齐；MANUAL：人工核对 */
    @Column(name = "source", nullable = false, length = 20)
    private String source;

    @Column(name = "frozen_at", nullable = false)
    private LocalDateTime frozenAt;

    @Column(name = "backfilled_at")
    private LocalDateTime backfilledAt;

    /** 待核对原因，如「原量体记录 ME-4003 已删除，无法补齐尺寸」 */
    @Column(name = "verify_note", length = 500)
    private String verifyNote;

    @Column(name = "confirmed_by", length = 50)
    private String confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

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

    public String getMeasureRemark() {
        return measureRemark;
    }

    public void setMeasureRemark(String measureRemark) {
        this.measureRemark = measureRemark;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public LocalDateTime getFrozenAt() {
        return frozenAt;
    }

    public void setFrozenAt(LocalDateTime frozenAt) {
        this.frozenAt = frozenAt;
    }

    public LocalDateTime getBackfilledAt() {
        return backfilledAt;
    }

    public void setBackfilledAt(LocalDateTime backfilledAt) {
        this.backfilledAt = backfilledAt;
    }

    public String getVerifyNote() {
        return verifyNote;
    }

    public void setVerifyNote(String verifyNote) {
        this.verifyNote = verifyNote;
    }

    public String getConfirmedBy() {
        return confirmedBy;
    }

    public void setConfirmedBy(String confirmedBy) {
        this.confirmedBy = confirmedBy;
    }

    public LocalDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(LocalDateTime confirmedAt) {
        this.confirmedAt = confirmedAt;
    }
}
