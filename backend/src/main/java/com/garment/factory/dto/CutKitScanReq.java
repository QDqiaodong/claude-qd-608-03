package com.garment.factory.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 逐片扫码入参（支持批量提交，扫码枪每扫一片触发一次或攒一批均可）。
 * 实际片按条码幂等：同套件同阶段重复条码不重复入账。
 */
public class CutKitScanReq {

    @NotBlank(message = "操作人不能为空")
    private String operator;

    @NotEmpty(message = "至少提交一片扫码记录")
    @Valid
    private List<ScanItem> pieces;

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public List<ScanItem> getPieces() {
        return pieces;
    }

    public void setPieces(List<ScanItem> pieces) {
        this.pieces = pieces;
    }

    /** 单片扫码记录：条码必填；部位/左右/批次/尺码由条码标签带出 */
    public static class ScanItem {

        @NotBlank(message = "裁片条码不能为空")
        private String pieceCode;

        @NotBlank(message = "部位不能为空")
        private String part;

        private String side;

        @NotBlank(message = "面料批次不能为空")
        private String fabricBatchNo;

        @NotBlank(message = "尺码标记不能为空")
        private String sizeMark;

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
    }
}
