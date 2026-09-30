package com.garment.factory.dto;

/**
 * 逐片差异项：封包校验与接收复核共用同一种结构。
 * type 取 CutKitDict.DIFF_*（缺片/重复片/多余片/左右混放/跨面料批次/尺码冲突）。
 */
public class CutPieceDiff {

    /** 差异类型 */
    private String type;
    /** 关联裁片条码（缺片时可为空） */
    private String pieceCode;
    /** 部位 */
    private String part;
    /** 左右侧 */
    private String side;
    /** 面料批次（冲突时带上扫到的值） */
    private String fabricBatchNo;
    /** 尺码标记（冲突时带上扫到的值） */
    private String sizeMark;
    /** 清单应有数量 */
    private int expectedQty;
    /** 实际数量 */
    private int actualQty;
    /** 中文说明，直接展示给裁剪/接收人员 */
    private String message;

    public CutPieceDiff() {
    }

    public CutPieceDiff(String type, String pieceCode, String part, String side, String fabricBatchNo,
                        String sizeMark, int expectedQty, int actualQty, String message) {
        this.type = type;
        this.pieceCode = pieceCode;
        this.part = part;
        this.side = side;
        this.fabricBatchNo = fabricBatchNo;
        this.sizeMark = sizeMark;
        this.expectedQty = expectedQty;
        this.actualQty = actualQty;
        this.message = message;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
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

    public int getExpectedQty() {
        return expectedQty;
    }

    public void setExpectedQty(int expectedQty) {
        this.expectedQty = expectedQty;
    }

    public int getActualQty() {
        return actualQty;
    }

    public void setActualQty(int actualQty) {
        this.actualQty = actualQty;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
