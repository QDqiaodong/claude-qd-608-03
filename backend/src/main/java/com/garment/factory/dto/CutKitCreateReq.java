package com.garment.factory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 建立裁片套件入参：裁剪人员按订单 + 裁剪批次建档。
 */
public class CutKitCreateReq {

    @NotBlank(message = "订单号不能为空")
    @Size(max = 30, message = "订单号过长")
    private String orderNo;

    @NotBlank(message = "裁剪批次号不能为空")
    @Size(max = 30, message = "裁剪批次号过长")
    private String cutBatchNo;

    @NotBlank(message = "服装名称不能为空")
    @Size(max = 80, message = "服装名称过长")
    private String garmentName;

    @NotBlank(message = "尺码标记不能为空")
    @Size(max = 20, message = "尺码标记过长")
    private String sizeMark;

    @NotBlank(message = "建档人不能为空")
    @Size(max = 50, message = "建档人姓名过长")
    private String operator;

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

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }
}
