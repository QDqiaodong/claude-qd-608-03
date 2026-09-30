package com.garment.factory.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * 整单替换套件应有清单（仅草稿态可改）。封包后清单冻结，改清单必须先拆包。
 */
public class CutKitManifestReq {

    @NotEmpty(message = "清单至少要有一片裁片")
    @Valid
    private List<CutKitPieceItemReq> items;

    @NotBlank(message = "操作人不能为空")
    @Size(max = 50, message = "操作人姓名过长")
    private String operator;

    @Size(max = 500, message = "备注过长")
    private String reason;

    public List<CutKitPieceItemReq> getItems() {
        return items;
    }

    public void setItems(List<CutKitPieceItemReq> items) {
        this.items = items;
    }

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
}
