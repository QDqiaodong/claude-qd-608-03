package com.garment.factory.domain;

import java.util.List;
import java.util.Map;

/**
 * 裁片套件模块字典：状态、裁片部位、左右侧、扫码阶段、事件类型与中文名。
 * 服务端校验与页面字典共用同一份口径。
 */
public final class CutKitDict {

    private CutKitDict() {
    }

    // ---------------- 套件状态 ----------------
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_SEALED = "SEALED";
    public static final String STATUS_HANDED_OVER = "HANDED_OVER";
    public static final String STATUS_RECEIVED = "RECEIVED";
    public static final String STATUS_EXCEPTION = "EXCEPTION";

    /** 正常流转顺序（异常态是岔路，补齐复核后回到已交接） */
    public static final List<String> STATUS_FLOW =
            List.of(STATUS_DRAFT, STATUS_SEALED, STATUS_HANDED_OVER, STATUS_RECEIVED);

    public static Map<String, String> statuses() {
        return Map.of(
                STATUS_DRAFT, "草稿",
                STATUS_SEALED, "已封包",
                STATUS_HANDED_OVER, "已交接",
                STATUS_RECEIVED, "已接收",
                STATUS_EXCEPTION, "异常");
    }

    public static String statusName(String code) {
        return statuses().getOrDefault(code, code);
    }

    // ---------------- 裁片部位 / 左右侧 ----------------
    public static final String SIDE_LEFT = "LEFT";
    public static final String SIDE_RIGHT = "RIGHT";
    public static final String SIDE_CENTER = "CENTER";

    public static Map<String, String> sides() {
        return Map.of(SIDE_LEFT, "左侧", SIDE_RIGHT, "右侧", SIDE_CENTER, "中片/不分左右");
    }

    public static final List<String> PARTS =
            List.of("FRONT", "BACK", "SLEEVE", "COLLAR", "LINING", "POCKET", "CUFF", "PLACKET", "INTERLINING", "OTHER");

    public static Map<String, String> parts() {
        return Map.ofEntries(
                Map.entry("FRONT", "前片"),
                Map.entry("BACK", "后片"),
                Map.entry("SLEEVE", "袖片"),
                Map.entry("COLLAR", "领片"),
                Map.entry("LINING", "里布"),
                Map.entry("POCKET", "袋片"),
                Map.entry("CUFF", "袖口"),
                Map.entry("PLACKET", "门襟"),
                Map.entry("INTERLINING", "衬布"),
                Map.entry("OTHER", "其他裁片"));
    }

    public static String partName(String code) {
        return parts().getOrDefault(code, code);
    }

    // ---------------- 扫码阶段 ----------------
    public static final String PHASE_EXPECTED = "EXPECTED";
    public static final String PHASE_PACKING = "PACKING";
    public static final String PHASE_RECEIVING = "RECEIVING";
    public static final String PHASE_RESOLVE = "RESOLVE";

    // ---------------- 事件类型 ----------------
    public static final String EVT_CREATE = "CREATE";
    public static final String EVT_UPDATE_MANIFEST = "UPDATE_MANIFEST";
    public static final String EVT_SCAN_PIECE = "SCAN_PIECE";
    public static final String EVT_REMOVE_SCAN = "REMOVE_SCAN";
    public static final String EVT_VALIDATE = "VALIDATE";
    public static final String EVT_SEAL = "SEAL";
    public static final String EVT_UNSEAL = "UNSEAL";
    public static final String EVT_HAND_OVER = "HAND_OVER";
    public static final String EVT_RECEIVE_FAIL = "RECEIVE_FAIL";
    public static final String EVT_RECEIVE = "RECEIVE";
    public static final String EVT_RAISE_EXCEPTION = "RAISE_EXCEPTION";
    public static final String EVT_RESOLVE_EXCEPTION = "RESOLVE_EXCEPTION";

    public static Map<String, String> eventTypes() {
        return Map.ofEntries(
                Map.entry(EVT_CREATE, "建立套件"),
                Map.entry(EVT_UPDATE_MANIFEST, "修改清单"),
                Map.entry(EVT_SCAN_PIECE, "扫码入套"),
                Map.entry(EVT_REMOVE_SCAN, "移除扫码"),
                Map.entry(EVT_VALIDATE, "封包校验"),
                Map.entry(EVT_SEAL, "封包"),
                Map.entry(EVT_UNSEAL, "拆包"),
                Map.entry(EVT_HAND_OVER, "交接"),
                Map.entry(EVT_RECEIVE_FAIL, "接收复核失败"),
                Map.entry(EVT_RECEIVE, "接收确认"),
                Map.entry(EVT_RAISE_EXCEPTION, "登记异常"),
                Map.entry(EVT_RESOLVE_EXCEPTION, "异常复核关闭"));
    }

    public static String eventName(String code) {
        return eventTypes().getOrDefault(code, code);
    }

    // ---------------- 差异类型 ----------------
    /** 缺片：清单有、实际无 */
    public static final String DIFF_MISSING = "MISSING";
    /** 重复片：同一裁片条码在同阶段出现多次，或同一(部位,侧)实扫数量超过清单 */
    public static final String DIFF_DUPLICATE = "DUPLICATE";
    /** 多余片：实际扫到清单里没有的裁片 */
    public static final String DIFF_SURPLUS = "SURPLUS";
    /** 左右混放：扫到的片左右侧与清单要求相反/不符 */
    public static final String DIFF_SIDE_MISMATCH = "SIDE_MISMATCH";
    /** 面料批次不一致 */
    public static final String DIFF_BATCH_CONFLICT = "BATCH_CONFLICT";
    /** 尺码标记冲突 */
    public static final String DIFF_SIZE_CONFLICT = "SIZE_CONFLICT";

    public static Map<String, String> diffTypes() {
        return Map.of(
                DIFF_MISSING, "缺片",
                DIFF_DUPLICATE, "重复片",
                DIFF_SURPLUS, "多余片",
                DIFF_SIDE_MISMATCH, "左右混放",
                DIFF_BATCH_CONFLICT, "跨面料批次",
                DIFF_SIZE_CONFLICT, "尺码标记冲突");
    }

    public static String diffName(String code) {
        return diffTypes().getOrDefault(code, code);
    }
}
