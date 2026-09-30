package com.garment.factory.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 套件裁片台账行：清单行（phase=EXPECTED）与实际扫码片（PACKING/RECEIVING/RESOLVE）同表。
 * 封包后的清单行与扫码历史永不更新删除；每轮复核重扫使用新阶段另存，保证全程可追溯。
 */
@Entity
@Table(name = "cut_kit_piece")
public class CutKitPiece {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kit_id", nullable = false)
    private Long kitId;

    /** EXPECTED / PACKING / RECEIVING / RESOLVE */
    @Column(name = "phase", nullable = false, length = 12)
    private String phase;

    @Column(name = "seq", nullable = false)
    private Integer seq = 0;

    /** 裁片条码：实际片必填且同套件同阶段唯一（uk_ckp_scan 兜底重复扫码）；清单行可空 */
    @Column(name = "piece_code", length = 60)
    private String pieceCode;

    @Column(name = "part", nullable = false, length = 30)
    private String part;

    /** LEFT / RIGHT / CENTER */
    @Column(name = "side", nullable = false, length = 10)
    private String side = CutKitDict.SIDE_CENTER;

    @Column(name = "fabric_batch_no", nullable = false, length = 30)
    private String fabricBatchNo;

    @Column(name = "size_mark", nullable = false, length = 20)
    private String sizeMark;

    /** 清单行可 >1 表示同类多片；实际扫码片恒为 1 */
    @Column(name = "quantity", nullable = false)
    private Integer quantity = 1;

    @Column(name = "scanned_by", length = 50)
    private String scannedBy;

    @Column(name = "scanned_at")
    private LocalDateTime scannedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getKitId() {
        return kitId;
    }

    public void setKitId(Long kitId) {
        this.kitId = kitId;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }

    public String getPieceCode() {
        return pieceCode;
    }

    public void setPieceCode(String pieceCode) {
        this.pieceCode = pieceCode;
    }

    public String getPart() {
        return part;
    }

    public void setPart(String part) {
        this.part = part;
    }

    public String getSide() {
        return side;
    }

    public void setSide(String side) {
        this.side = side;
    }

    public String getFabricBatchNo() {
        return fabricBatchNo;
    }

    public void setFabricBatchNo(String fabricBatchNo) {
        this.fabricBatchNo = fabricBatchNo;
    }

    public String getSizeMark() {
        return sizeMark;
    }

    public void setSizeMark(String sizeMark) {
        this.sizeMark = sizeMark;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public String getScannedBy() {
        return scannedBy;
    }

    public void setScannedBy(String scannedBy) {
        this.scannedBy = scannedBy;
    }

    public LocalDateTime getScannedAt() {
        return scannedAt;
    }

    public void setScannedAt(LocalDateTime scannedAt) {
        this.scannedAt = scannedAt;
    }
}
