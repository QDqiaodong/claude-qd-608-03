package com.garment.factory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 清单行入参：应有裁片逐项登记部位、左右侧、面料批次、尺码标记与数量。
 */
public class CutKitPieceItemReq {

    @NotBlank(message = "部位不能为空")
    private String part;

    /** LEFT / RIGHT / CENTER，默认 CENTER */
    private String side;

    @NotBlank(message = "面料批次不能为空")
    @Size(max = 30, message = "面料批次号过长")
    private String fabricBatchNo;

    @NotBlank(message = "尺码标记不能为空")
    @Size(max = 20, message = "尺码标记过长")
    private String sizeMark;

    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "数量必须大于 0")
    private Integer quantity;

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
}
