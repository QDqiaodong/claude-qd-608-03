package com.garment.factory.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 裁片套件：同一件定制服装、同一裁剪批次的前片/后片/袖片/领片/里布等裁片组成的可追踪封包单元。
 * 状态机：DRAFT(草稿) → SEALED(已封包) → HANDED_OVER(已交接) → RECEIVED(已接收)；
 * 交接后发现漏片/混片进入 EXCEPTION，补齐复核后回到 HANDED_OVER。
 */
@Entity
@Table(name = "cut_kit")
public class CutKit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kit_no", nullable = false, length = 30)
    private String kitNo;

    /** 订单号：业务归属，不与 custom_order 建强外键（套件可先行建档） */
    @Column(name = "order_no", nullable = false, length = 30)
    private String orderNo;

    @Column(name = "cut_batch_no", nullable = false, length = 30)
    private String cutBatchNo;

    @Column(name = "garment_name", nullable = false, length = 80)
    private String garmentName;

    @Column(name = "size_mark", nullable = false, length = 20)
    private String sizeMark;

    /** DRAFT / SEALED / HANDED_OVER / RECEIVED / EXCEPTION */
    @Column(name = "status", nullable = false, length = 20)
    private String status = CutKitDict.STATUS_DRAFT;

    @Column(name = "expected_total", nullable = false)
    private Integer expectedTotal = 0;

    @Column(name = "sealed_at")
    private LocalDateTime sealedAt;

    @Column(name = "sealed_by", length = 50)
    private String sealedBy;

    @Column(name = "seal_no", length = 40)
    private String sealNo;

    @Column(name = "handed_over_at")
    private LocalDateTime handedOverAt;

    @Column(name = "handed_by", length = 50)
    private String handedBy;

    @Column(name = "receiver_name", length = 50)
    private String receiverName;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    @Column(name = "received_by", length = 50)
    private String receivedBy;

    @Column(name = "exception_no", length = 30)
    private String exceptionNo;

    @Column(name = "exception_count", nullable = false)
    private Integer exceptionCount = 0;

    @Column(name = "created_by", nullable = false, length = 50)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getKitNo() {
        return kitNo;
    }

    public void setKitNo(String kitNo) {
        this.kitNo = kitNo;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getCutBatchNo() {
        return cutBatchNo;
    }

    public void setCutBatchNo(String cutBatchNo) {
        this.cutBatchNo = cutBatchNo;
    }

    public String getGarmentName() {
        return garmentName;
    }

    public void setGarmentName(String garmentName) {
        this.garmentName = garmentName;
    }

    public String getSizeMark() {
        return sizeMark;
    }

    public void setSizeMark(String sizeMark) {
        this.sizeMark = sizeMark;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getExpectedTotal() {
        return expectedTotal;
    }

    public void setExpectedTotal(Integer expectedTotal) {
        this.expectedTotal = expectedTotal;
    }

    public LocalDateTime getSealedAt() {
        return sealedAt;
    }

    public void setSealedAt(LocalDateTime sealedAt) {
        this.sealedAt = sealedAt;
    }

    public String getSealedBy() {
        return sealedBy;
    }

    public void setSealedBy(String sealedBy) {
        this.sealedBy = sealedBy;
    }

    public String getSealNo() {
        return sealNo;
    }

    public void setSealNo(String sealNo) {
        this.sealNo = sealNo;
    }

    public LocalDateTime getHandedOverAt() {
        return handedOverAt;
    }

    public void setHandedOverAt(LocalDateTime handedOverAt) {
        this.handedOverAt = handedOverAt;
    }

    public String getHandedBy() {
        return handedBy;
    }

    public void setHandedBy(String handedBy) {
        this.handedBy = handedBy;
    }

    public String getReceiverName() {
        return receiverName;
    }

    public void setReceiverName(String receiverName) {
        this.receiverName = receiverName;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(LocalDateTime receivedAt) {
        this.receivedAt = receivedAt;
    }

    public String getReceivedBy() {
        return receivedBy;
    }

    public void setReceivedBy(String receivedBy) {
        this.receivedBy = receivedBy;
    }

    public String getExceptionNo() {
        return exceptionNo;
    }

    public void setExceptionNo(String exceptionNo) {
        this.exceptionNo = exceptionNo;
    }

    public Integer getExceptionCount() {
        return exceptionCount;
    }

    public void setExceptionCount(Integer exceptionCount) {
        this.exceptionCount = exceptionCount;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
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

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
