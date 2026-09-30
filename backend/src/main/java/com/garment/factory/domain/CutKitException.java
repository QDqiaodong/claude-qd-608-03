package com.garment.factory.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 套件异常单：交接后发现漏片/混片时登记。OPEN 期间套件处于 EXCEPTION，
 * 补齐并经复核关闭（RESOLVED）后套件回到 HANDED_OVER，差异项与处理人完整保留。
 */
@Entity
@Table(name = "cut_kit_exception")
public class CutKitException {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exception_no", nullable = false, length = 30)
    private String exceptionNo;

    @Column(name = "kit_id", nullable = false)
    private Long kitId;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    /** 发现时的全部差异项 JSON（缺片/混片/批次/尺码等） */
    @Column(name = "discrepancies_json", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String discrepanciesJson;

    @Column(name = "raised_by", nullable = false, length = 50)
    private String raisedBy;

    @Column(name = "raised_at", nullable = false)
    private LocalDateTime raisedAt;

    /** OPEN / RESOLVED */
    @Column(name = "status", nullable = false, length = 10)
    private String status = "OPEN";

    @Column(name = "resolved_by", length = 50)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolve_note", length = 500)
    private String resolveNote;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getExceptionNo() {
        return exceptionNo;
    }

    public void setExceptionNo(String exceptionNo) {
        this.exceptionNo = exceptionNo;
    }

    public Long getKitId() {
        return kitId;
    }

    public void setKitId(Long kitId) {
        this.kitId = kitId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getDiscrepanciesJson() {
        return discrepanciesJson;
    }

    public void setDiscrepanciesJson(String discrepanciesJson) {
        this.discrepanciesJson = discrepanciesJson;
    }

    public String getRaisedBy() {
        return raisedBy;
    }

    public void setRaisedBy(String raisedBy) {
        this.raisedBy = raisedBy;
    }

    public LocalDateTime getRaisedAt() {
        return raisedAt;
    }

    public void setRaisedAt(LocalDateTime raisedAt) {
        this.raisedAt = raisedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(String resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public LocalDateTime getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(LocalDateTime resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public String getResolveNote() {
        return resolveNote;
    }

    public void setResolveNote(String resolveNote) {
        this.resolveNote = resolveNote;
    }
}
