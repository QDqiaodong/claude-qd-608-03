package com.garment.factory.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.garment.factory.common.ApiResponse;
import com.garment.factory.domain.CustomOrder;
import com.garment.factory.domain.GarmentDict;
import com.garment.factory.dto.CustomerOptionDto;
import com.garment.factory.dto.MeasurementConfirmReq;
import com.garment.factory.dto.OrderAdvanceReq;
import com.garment.factory.dto.OrderCreateReq;
import com.garment.factory.dto.OrderDetailDto;
import com.garment.factory.dto.OrderListItemDto;
import com.garment.factory.dto.PricePreviewDto;
import com.garment.factory.dto.PricePreviewReq;
import com.garment.factory.dto.SliceView;
import com.garment.factory.service.OrderService;
import com.garment.factory.service.OrderSnapshotService;

/**
 * 定制订单：向导式下单、订单列表、进度看板、状态流转、尾款结算。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderSnapshotService snapshotService;

    public OrderController(OrderService orderService, OrderSnapshotService snapshotService) {
        this.orderService = orderService;
        this.snapshotService = snapshotService;
    }

    /** 列表：JPQL 构造器表达式投影 + Slice 滚动分页 */
    @GetMapping
    public ApiResponse<SliceView<OrderListItemDto>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "8") int size) {
        return ApiResponse.success(orderService.list(status, customerId, keyword, page, size));
    }

    /** 订单进度看板：按状态分列 */
    @GetMapping("/board")
    public ApiResponse<Map<String, Object>> board() {
        return ApiResponse.success(orderService.board());
    }

    /** 下单向导第 1 步：客户候选 */
    @GetMapping("/customers")
    public ApiResponse<List<CustomerOptionDto>> customers() {
        return ApiResponse.success(orderService.customers());
    }

    @GetMapping("/{id}")
    public ApiResponse<OrderDetailDto> detail(@PathVariable Long id) {
        return ApiResponse.success(orderService.detail(id));
    }

    /**
     * 历史订单量体快照补齐（可重复执行，幂等）：
     * 原记录在的订单冻结原尺寸，原记录缺失的订单标待核对；重复执行只形成一份快照。
     */
    @PostMapping("/measurement-snapshots/backfill")
    public ApiResponse<Map<String, Object>> backfillSnapshots() {
        Map<String, Object> result = snapshotService.backfillAll();
        int frozen = ((Number) result.getOrDefault("frozen", 0)).intValue();
        int missing = ((Number) result.getOrDefault("markedMissing", 0)).intValue();
        int scanned = ((Number) result.getOrDefault("scanned", 0)).intValue();
        String message;
        if (scanned == 0) {
            message = "所有订单均已有量体快照，无需重复补齐";
        } else {
            message = "已补齐 " + frozen + " 笔订单的量体快照";
            if (missing > 0) {
                message += "；" + missing + " 笔原量体记录已缺失，已标记待核对";
            }
        }
        return ApiResponse.success(message, result);
    }

    /** 原量体记录缺失的订单，人工核对尺寸后补录确认（自动补齐永不覆盖该结果） */
    @PostMapping("/{id}/measurement-confirm")
    public ApiResponse<CustomOrder> confirmMeasurement(@PathVariable Long id,
                                                       @RequestBody MeasurementConfirmReq req) {
        snapshotService.confirmMissing(id, req);
        return ApiResponse.success("量体尺寸已人工核对确认并冻结", orderService.detail(id).getOrder());
    }

    /** 下单向导第 4 步：实时算价 */
    @PostMapping("/price-preview")
    public ApiResponse<PricePreviewDto> preview(@RequestBody PricePreviewReq req) {
        return ApiResponse.success(orderService.preview(req));
    }

    /** 下单向导：提交订单 */
    @PostMapping
    public ApiResponse<CustomOrder> create(@RequestBody OrderCreateReq req) {
        return ApiResponse.success("定制订单已建立，工序工单已投产", orderService.create(req));
    }

    /** 状态流转：默认推进到下一状态；试衣完工时普通问题放行需显式确认 */
    @PostMapping("/{id}/advance")
    public ApiResponse<CustomOrder> advance(@PathVariable Long id,
                                            @RequestBody(required = false) OrderAdvanceReq req) {
        return ApiResponse.success("订单状态已推进", orderService.advance(id, req));
    }

    /** 结清尾款 */
    @PostMapping("/{id}/balance-pay")
    public ApiResponse<CustomOrder> payBalance(@PathVariable Long id) {
        return ApiResponse.success("尾款已结清", orderService.payBalance(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> remove(@PathVariable Long id) {
        orderService.remove(id);
        return ApiResponse.success("订单已撤销，面料库存已归还", null);
    }

    /** 订单状态选项与流转顺序 */
    @GetMapping("/meta")
    public ApiResponse<Map<String, Object>> meta() {
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("statuses", GarmentDict.orderStatuses());
        meta.put("statusOrder", GarmentDict.orderStatusOrder());
        return ApiResponse.success(meta);
    }
}
