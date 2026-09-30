package com.garment.factory.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.garment.factory.common.ApiResponse;
import com.garment.factory.domain.CutKitDict;
import com.garment.factory.dto.CutKitActionReq;
import com.garment.factory.dto.CutKitCreateReq;
import com.garment.factory.dto.CutKitManifestReq;
import com.garment.factory.dto.CutKitScanReq;
import com.garment.factory.dto.SliceView;
import com.garment.factory.service.cut.CutKitService;

import jakarta.validation.Valid;

/**
 * 裁片套件封包交接：建档 → 清单 → 逐片扫码 → 封包校验 → 交接 → 接收复核 → 异常处理 → 时间线。
 * 核心规则、并发控制、幂等与状态机全部在服务端事务内保证。
 */
@RestController
@RequestMapping("/api/cut-kits")
public class CutKitController {

    private final CutKitService cutKitService;

    public CutKitController(CutKitService cutKitService) {
        this.cutKitService = cutKitService;
    }

    /** 套件列表：按状态/关键字过滤，刷新后状态一致 */
    @GetMapping
    public ApiResponse<SliceView<Map<String, Object>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(cutKitService.list(status, keyword, page, size));
    }

    /** 套件详情：清单/各阶段扫码/封签/交接接收信息/异常单/最新校验结果 */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.success(cutKitService.detail(id));
    }

    /** 不可覆盖的事件时间线（状态变化、差异项、处理人、拆包前后快照） */
    @GetMapping("/{id}/timeline")
    public ApiResponse<List<Map<String, Object>>> timeline(@PathVariable Long id) {
        return ApiResponse.success(cutKitService.timeline(id));
    }

    /** 建立套件（草稿态） */
    @PostMapping
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody CutKitCreateReq req) {
        return ApiResponse.success("套件已建立", cutKitService.create(req));
    }

    /** 整单保存应有清单（仅草稿态；封包后必须先拆包） */
    @PostMapping("/{id}/manifest")
    public ApiResponse<Map<String, Object>> manifest(@PathVariable Long id,
                                                     @Valid @RequestBody CutKitManifestReq req) {
        return ApiResponse.success("清单已保存", cutKitService.saveManifest(id, req));
    }

    /** 逐片扫码（草稿态封包阶段，重复条码幂等） */
    @PostMapping("/{id}/scans")
    public ApiResponse<Map<String, Object>> scan(@PathVariable Long id, @Valid @RequestBody CutKitScanReq req) {
        return ApiResponse.success("扫码完成", cutKitService.scanPieces(id, req));
    }

    /** 移除误扫裁片（仅草稿态，动作仍进时间线） */
    @PostMapping("/{id}/scans/{pieceId}/remove")
    public ApiResponse<Map<String, Object>> removeScan(@PathVariable Long id, @PathVariable Long pieceId,
                                                       @RequestBody(required = false) Map<String, String> body) {
        String operator = body == null ? null : body.get("operator");
        return ApiResponse.success("已移除扫码记录", cutKitService.removeScan(id, pieceId, operator));
    }

    /** 封包校验（只校验不改状态，随扫随看差异） */
    @GetMapping("/{id}/validate")
    public ApiResponse<Map<String, Object>> validate(@PathVariable Long id) {
        return ApiResponse.success(cutKitService.validate(id));
    }

    /** 封包：差异为零才成功，否则拦截 */
    @PostMapping("/{id}/seal")
    public ApiResponse<Map<String, Object>> seal(@PathVariable Long id, @Valid @RequestBody CutKitActionReq req) {
        return ApiResponse.success("封包成功，已生成封签", cutKitService.seal(id, req));
    }

    /** 拆包：仅已封包态可拆，必须填原因，保留前后完整快照 */
    @PostMapping("/{id}/unseal")
    public ApiResponse<Map<String, Object>> unseal(@PathVariable Long id, @Valid @RequestBody CutKitActionReq req) {
        return ApiResponse.success("已拆包，清单可修改，拆包记录已留痕", cutKitService.unseal(id, req));
    }

    /** 交接：已封包 → 已交接，记录交出人/接收人/扫描时间，重复请求幂等，并发只一次成功 */
    @PostMapping("/{id}/hand-over")
    public ApiResponse<Map<String, Object>> handOver(@PathVariable Long id,
                                                     @Valid @RequestBody CutKitActionReq req) {
        return ApiResponse.success("交接成功，等待接收方核对", cutKitService.handOver(id, req));
    }

    /** 接收：核对封签与裁片数量（可逐片重扫）；失败按规则回滚或转异常 */
    @PostMapping("/{id}/receive")
    public ApiResponse<Map<String, Object>> receive(@PathVariable Long id,
                                                    @Valid @RequestBody CutKitActionReq req) {
        return ApiResponse.success("接收成功，套件交接闭环完成", cutKitService.receive(id, req));
    }

    /** 登记异常：已交接套件发现漏片/混片 → 异常状态（不回草稿） */
    @PostMapping("/{id}/exceptions")
    public ApiResponse<Map<String, Object>> raiseException(@PathVariable Long id,
                                                           @Valid @RequestBody CutKitActionReq req) {
        return ApiResponse.success("已登记异常，套件进入异常处理", cutKitService.raiseException(id, req));
    }

    /** 异常处理阶段逐片补扫 */
    @PostMapping("/{id}/resolve-scans")
    public ApiResponse<Map<String, Object>> resolveScans(@PathVariable Long id,
                                                         @Valid @RequestBody CutKitScanReq req) {
        return ApiResponse.success("补扫完成，可提交复核", cutKitService.resolveScans(id, req));
    }

    /** 异常复核关闭：补齐后与清单完全一致才回到已交接 */
    @PostMapping("/{id}/resolve-review")
    public ApiResponse<Map<String, Object>> resolveReview(@PathVariable Long id,
                                                          @Valid @RequestBody CutKitActionReq req) {
        return ApiResponse.success("复核通过，异常已关闭，套件回到已交接",
                cutKitService.resolveReview(id, req));
    }

    /** 模块字典：状态/部位/左右侧/事件/差异类型 */
    @GetMapping("/meta")
    public ApiResponse<Map<String, Object>> meta() {
        return ApiResponse.success(Map.of(
                "statuses", CutKitDict.statuses(),
                "statusFlow", CutKitDict.STATUS_FLOW,
                "parts", CutKitDict.parts(),
                "sides", CutKitDict.sides(),
                "eventTypes", CutKitDict.eventTypes(),
                "diffTypes", CutKitDict.diffTypes()));
    }
}
