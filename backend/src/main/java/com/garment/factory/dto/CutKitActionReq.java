package com.garment.factory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 套件关键动作统一入参：封包 / 拆包 / 交接 / 接收 / 异常登记 / 异常复核。
 * 各动作只取自己关心的字段，服务端按动作分别校验必填。
 */
public class CutKitActionReq {

    /** 操作人（封包人 / 拆包人 / 交出人 / 接收操作人 / 异常登记人 / 复核人） */
    @NotBlank(message = "操作人不能为空")
    @Size(max = 50, message = "操作人姓名过长")
    private String operator;

    /** 拆包原因 / 异常情况说明 / 异常复核处理说明（对应动作必填） */
    @Size(max = 500, message = "原因说明过长")
    private String reason;

    /** 交接时登记的指定接收人（交接动作必填） */
    @Size(max = 50, message = "接收人姓名过长")
    private String receiverName;

    /** 接收方核对的封签编号（接收动作必填，与封包封签不一致直接回滚） */
    @Size(max = 40, message = "封签编号过长")
    private String sealNo;

    /**
     * 接收方/异常复核时逐片重扫的裁片（可选；提供时服务端做全量比对）。
     * 结构与逐片扫码接口一致：[{pieceCode, part, side, fabricBatchNo, sizeMark}]
     */
    private java.util.List<CutKitScanReq.ScanItem> pieces;

    /** 前端页面版本号（可空）：过期页面的提交直接拒绝，以服务端最新状态为准 */
    private Integer expectedVersion;

    /** 幂等请求号（交接/接收建议必传）：同号重放只返回首次结果 */
    @Size(max = 60, message = "请求号过长")
    private String requestId;

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getReceiverName() {
        return receiverName;
    }

    public void setReceiverName(String receiverName) {
        this.receiverName = receiverName;
    }

    public String getSealNo() {
        return sealNo;
    }

    public void setSealNo(String sealNo) {
        this.sealNo = sealNo;
    }

    public java.util.List<CutKitScanReq.ScanItem> getPieces() {
        return pieces;
    }

    public void setPieces(java.util.List<CutKitScanReq.ScanItem> pieces) {
        this.pieces = pieces;
    }

    public Integer getExpectedVersion() {
        return expectedVersion;
    }

    public void setExpectedVersion(Integer expectedVersion) {
        this.expectedVersion = expectedVersion;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
}
