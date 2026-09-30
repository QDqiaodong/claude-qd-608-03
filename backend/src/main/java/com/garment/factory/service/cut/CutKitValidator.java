package com.garment.factory.service.cut;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.garment.factory.common.BizException;
import com.garment.factory.domain.CutKitDict;
import com.garment.factory.dto.CutKitPieceItemReq;
import com.garment.factory.dto.CutKitScanReq;
import com.garment.factory.dto.CutPieceDiff;

/**
 * 裁片核对引擎（纯函数、无副作用）：把「应有清单」与「实际裁片」逐项比对，
 * 识别缺片、重复片、多余片、左右混放、跨面料批次、尺码标记冲突。
 *
 * 口径：
 *  - 清单按 (部位, 左右侧) 归组，每组记录应有数量与该组要求的面料批次/尺码；
 *  - 实际片按条码去重后，先按条码发现重复片，再按 (部位, 左右侧) 归组计实际数量；
 *  - 部位在清单里存在但左右侧对不上（如清单要左袖、扫到右袖）判为左右混放；
 *  - 同组实际片面料批次/尺码与清单不符分别判为跨面料批次 / 尺码冲突；
 *  - 实际多出来的（部位或左右均无法归入清单）判为多余片。
 * 只有差异为空且实际总数 = 应有总数时才允许封包/接收通过。
 */
public final class CutKitValidator {

    private CutKitValidator() {
    }

    /** 清单归组视图 */
    public static class ExpectedGroup {
        final String part;
        final String side;
        int expectedQty;
        final Map<String, Integer> batchCounts = new LinkedHashMap<>();
        final Map<String, Integer> sizeCounts = new LinkedHashMap<>();

        ExpectedGroup(String part, String side) {
            this.part = part;
            this.side = side;
        }

        String requiredBatch() {
            return batchCounts.keySet().iterator().next();
        }

        String requiredSize() {
            return sizeCounts.keySet().iterator().next();
        }
    }

    /** 实际片归组视图 */
    public static class ActualGroup {
        final String part;
        final String side;
        final List<CutKitScanReq.ScanItem> items = new ArrayList<>();
        final Map<String, Integer> batchCounts = new LinkedHashMap<>();
        final Map<String, Integer> sizeCounts = new LinkedHashMap<>();

        ActualGroup(String part, String side) {
            this.part = part;
            this.side = side;
        }
    }

    public static String normalizeSide(String side) {
        if (side == null || side.isBlank()) {
            return CutKitDict.SIDE_CENTER;
        }
        String s = side.trim().toUpperCase();
        if (!List.of(CutKitDict.SIDE_LEFT, CutKitDict.SIDE_RIGHT, CutKitDict.SIDE_CENTER).contains(s)) {
            throw new BizException("左右侧只能是 LEFT/RIGHT/CENTER");
        }
        return s;
    }

    public static String normalizePart(String part) {
        String p = part == null ? "" : part.trim().toUpperCase();
        if (!CutKitDict.PARTS.contains(p)) {
            throw new BizException("未知裁片部位：" + part);
        }
        return p;
    }

    private static String key(String part, String side) {
        return part + "#" + side;
    }

    /** 把清单请求归组（同时校验清单自身：同组批次/尺码必须一致，否则建档就拦） */
    public static Map<String, ExpectedGroup> indexManifest(List<CutKitPieceItemReq> items) {
        Map<String, ExpectedGroup> groups = new LinkedHashMap<>();
        for (CutKitPieceItemReq item : items) {
            String part = normalizePart(item.getPart());
            String side = normalizeSide(item.getSide());
            int qty = item.getQuantity() == null ? 0 : item.getQuantity();
            if (qty <= 0) {
                throw new BizException(CutKitDict.partName(part) + " 数量必须大于 0");
            }
            ExpectedGroup g = groups.computeIfAbsent(key(part, side), k -> new ExpectedGroup(part, side));
            g.expectedQty += qty;
            g.batchCounts.merge(item.getFabricBatchNo().trim(), qty, Integer::sum);
            g.sizeCounts.merge(item.getSizeMark().trim(), qty, Integer::sum);
        }
        // 清单自检：同一(部位,侧)不允许出现两个面料批次或两个尺码标记（应有清单必须自洽）
        for (ExpectedGroup g : groups.values()) {
            if (g.batchCounts.size() > 1) {
                throw new BizException("清单自身冲突：" + CutKitDict.partName(g.part) + label(g.side)
                        + " 同时登记了多个面料批次，请先统一");
            }
            if (g.sizeCounts.size() > 1) {
                throw new BizException("清单自身冲突：" + CutKitDict.partName(g.part) + label(g.side)
                        + " 同时登记了多个尺码标记，请先统一");
            }
        }
        return groups;
    }

    /**
     * 核心比对。actualPieces 已按条码去重（重复条码在入库层拦截，这里对入参批次再防御一次）。
     * 返回有序差异列表；空列表即「实际裁片与应有清单完全一致」。
     */
    public static List<CutPieceDiff> compare(List<CutKitPieceItemReq> manifestItems,
                                             List<CutKitScanReq.ScanItem> actualPieces) {
        Map<String, ExpectedGroup> expected = indexManifest(manifestItems);
        return compareIndexed(expected, actualPieces);
    }

    /** 封包阶段使用已持久化的清单分组，避免重复构造 */
    public static List<CutPieceDiff> compareIndexed(Map<String, ExpectedGroup> expected,
                                                    List<CutKitScanReq.ScanItem> actualPieces) {
        List<CutPieceDiff> diffs = new ArrayList<>();

        // 1) 条码重复：同一批实际片里同一 pieceCode 出现多次
        Map<String, Integer> codeCount = new LinkedHashMap<>();
        Map<String, CutKitScanReq.ScanItem> codeFirst = new LinkedHashMap<>();
        for (CutKitScanReq.ScanItem p : actualPieces) {
            String code = p.getPieceCode().trim();
            codeCount.merge(code, 1, Integer::sum);
            codeFirst.putIfAbsent(code, p);
        }
        List<CutKitScanReq.ScanItem> deduped = new ArrayList<>();
        for (Map.Entry<String, Integer> e : codeCount.entrySet()) {
            CutKitScanReq.ScanItem first = codeFirst.get(e.getKey());
            if (e.getValue() > 1) {
                diffs.add(new CutPieceDiff(CutKitDict.DIFF_DUPLICATE, e.getKey(),
                        normalizePart(first.getPart()), normalizeSide(first.getSide()),
                        first.getFabricBatchNo(), first.getSizeMark(),
                        1, e.getValue(),
                        "裁片条码 " + e.getKey() + " 重复扫描 " + e.getValue() + " 次，同一裁片不能入套两次"));
            }
            deduped.add(first);
        }

        // 2) 实际片按 (部位,侧) 归组
        Map<String, ActualGroup> actualGroups = new LinkedHashMap<>();
        for (CutKitScanReq.ScanItem p : deduped) {
            String part = normalizePart(p.getPart());
            String side = normalizeSide(p.getSide());
            ActualGroup g = actualGroups.computeIfAbsent(key(part, side), k -> new ActualGroup(part, side));
            g.items.add(p);
            g.batchCounts.merge(p.getFabricBatchNo().trim(), 1, Integer::sum);
            g.sizeCounts.merge(p.getSizeMark().trim(), 1, Integer::sum);
        }

        // 3) 部位级左右对账（CENTER 组独立处理）
        // 每个 (部位,侧) 先算缺额 deficit 与多额 surplus；一侧多出且镜像侧有缺额 → 定性为左右混放，
        // 多额超出镜像缺额的部分仍是重复片，剩余缺额是缺片。这样左右对调不会重复定性，也不会漏报。
        for (String part : partsOf(expected)) {
            ExpectedGroup cg = expected.get(key(part, CutKitDict.SIDE_CENTER));
            ActualGroup cga = actualGroups.get(key(part, CutKitDict.SIDE_CENTER));
            if (cg != null) {
                reconcileGroup(cg, cga, diffs);
                actualGroups.remove(key(part, CutKitDict.SIDE_CENTER));
            } else if (cga != null) {
                // 清单没有中片却扫到中片 → 多余片
                for (CutKitScanReq.ScanItem p : cga.items) {
                    diffs.add(surplus(p));
                }
                actualGroups.remove(key(part, CutKitDict.SIDE_CENTER));
            }

            ExpectedGroup lg = expected.get(key(part, CutKitDict.SIDE_LEFT));
            ExpectedGroup rg = expected.get(key(part, CutKitDict.SIDE_RIGHT));
            ActualGroup lga = actualGroups.get(key(part, CutKitDict.SIDE_LEFT));
            ActualGroup rga = actualGroups.get(key(part, CutKitDict.SIDE_RIGHT));
            int lExp = lg == null ? 0 : lg.expectedQty;
            int rExp = rg == null ? 0 : rg.expectedQty;
            int lAct = lga == null ? 0 : lga.items.size();
            int rAct = rga == null ? 0 : rga.items.size();
            int lDef = Math.max(0, lExp - lAct);
            int rDef = Math.max(0, rExp - rAct);
            int lSur = Math.max(0, lAct - lExp);
            int rSur = Math.max(0, rAct - rExp);

            // 左多右缺：多出的左片填右侧缺额 → 左右混放
            int lToR = Math.min(lSur, rDef);
            for (int i = 0; i < lToR; i++) {
                CutKitScanReq.ScanItem p = lga.items.get(lExp + i);
                diffs.add(sideMismatch(p, part, CutKitDict.SIDE_LEFT, CutKitDict.SIDE_RIGHT));
            }
            // 右多左缺
            int rToL = Math.min(rSur, lDef);
            for (int i = 0; i < rToL; i++) {
                CutKitScanReq.ScanItem p = rga.items.get(rExp + i);
                diffs.add(sideMismatch(p, part, CutKitDict.SIDE_RIGHT, CutKitDict.SIDE_LEFT));
            }
            // 混放后仍有的缺额 → 缺片
            if (lg != null && lDef - rToL > 0) {
                diffs.add(missing(lg, lAct));
            }
            if (rg != null && rDef - lToR > 0) {
                diffs.add(missing(rg, rAct));
            }
            // 混放后仍有的多额 → 重复片（同部位同侧多扫）
            int lDup = lSur - lToR;
            if (lDup > 0) {
                diffs.add(duplicateGroup(lg == null ? rg : lg, CutKitDict.SIDE_LEFT, lAct, lExp));
            }
            int rDup = rSur - rToL;
            if (rDup > 0) {
                diffs.add(duplicateGroup(rg == null ? lg : rg, CutKitDict.SIDE_RIGHT, rAct, rExp));
            }

            // 批次/尺码内容核对（对已按数匹配或镜像存在的组）
            if (lg != null && lga != null) {
                contentChecks(lg, lga, diffs);
            }
            if (rg != null && rga != null) {
                contentChecks(rg, rga, diffs);
            }
            actualGroups.remove(key(part, CutKitDict.SIDE_LEFT));
            actualGroups.remove(key(part, CutKitDict.SIDE_RIGHT));
        }

        // 4) 剩余未消费的实际组全部是多余片（部位在清单中完全不存在）
        for (ActualGroup g : actualGroups.values()) {
            for (CutKitScanReq.ScanItem p : g.items) {
                diffs.add(surplus(p));
            }
        }

        return diffs;
    }

    private static List<String> partsOf(Map<String, ExpectedGroup> groups) {
        List<String> parts = new ArrayList<>();
        for (ExpectedGroup g : groups.values()) {
            if (!parts.contains(g.part)) {
                parts.add(g.part);
            }
        }
        return parts;
    }

    /** CENTER 组核对：缺片/重复片 */
    private static void reconcileGroup(ExpectedGroup exp, ActualGroup act, List<CutPieceDiff> diffs) {
        int actualQty = act == null ? 0 : act.items.size();
        if (actualQty < exp.expectedQty) {
            diffs.add(missing(exp, actualQty));
        } else if (actualQty > exp.expectedQty) {
            diffs.add(duplicateGroup(exp, exp.side, actualQty, exp.expectedQty));
        }
        if (act != null) {
            contentChecks(exp, act, diffs);
        }
    }

    private static void contentChecks(ExpectedGroup exp, ActualGroup act, List<CutPieceDiff> diffs) {
        for (Map.Entry<String, Integer> b : act.batchCounts.entrySet()) {
            if (!b.getKey().equals(exp.requiredBatch())) {
                CutKitScanReq.ScanItem sample = act.items.stream()
                        .filter(i -> i.getFabricBatchNo().trim().equals(b.getKey())).findFirst().orElse(null);
                diffs.add(new CutPieceDiff(CutKitDict.DIFF_BATCH_CONFLICT,
                        sample == null ? null : sample.getPieceCode(),
                        exp.part, exp.side, b.getKey(), exp.requiredSize(),
                        exp.expectedQty, b.getValue(),
                        "跨面料批次：" + CutKitDict.partName(exp.part) + label(exp.side)
                                + " 清单批次 " + exp.requiredBatch() + "，实际混入批次 " + b.getKey()
                                + " 共 " + b.getValue() + " 片"));
            }
        }
        for (Map.Entry<String, Integer> s : act.sizeCounts.entrySet()) {
            if (!s.getKey().equals(exp.requiredSize())) {
                CutKitScanReq.ScanItem sample = act.items.stream()
                        .filter(i -> i.getSizeMark().trim().equals(s.getKey())).findFirst().orElse(null);
                diffs.add(new CutPieceDiff(CutKitDict.DIFF_SIZE_CONFLICT,
                        sample == null ? null : sample.getPieceCode(),
                        exp.part, exp.side, exp.requiredBatch(), s.getKey(),
                        exp.expectedQty, s.getValue(),
                        "尺码标记冲突：" + CutKitDict.partName(exp.part) + label(exp.side)
                                + " 清单标记 " + exp.requiredSize() + "，实际扫到 " + s.getKey()
                                + " 共 " + s.getValue() + " 片"));
            }
        }
    }

    private static CutPieceDiff missing(ExpectedGroup exp, int actualQty) {
        return new CutPieceDiff(CutKitDict.DIFF_MISSING, null, exp.part, exp.side,
                exp.requiredBatch(), exp.requiredSize(), exp.expectedQty, actualQty,
                "缺片：" + CutKitDict.partName(exp.part) + label(exp.side) + " 应有 "
                        + exp.expectedQty + " 片，实际 " + actualQty + " 片，缺 "
                        + (exp.expectedQty - actualQty) + " 片");
    }

    private static CutPieceDiff duplicateGroup(ExpectedGroup exp, String side, int actualQty, int expectedQty) {
        return new CutPieceDiff(CutKitDict.DIFF_DUPLICATE, null, exp.part, side,
                exp.requiredBatch(), exp.requiredSize(), expectedQty, actualQty,
                "重复片：" + CutKitDict.partName(exp.part) + label(side) + " 应有 "
                        + expectedQty + " 片，实际 " + actualQty + " 片，多 "
                        + (actualQty - expectedQty) + " 片");
    }

    private static CutPieceDiff sideMismatch(CutKitScanReq.ScanItem p, String part, String scannedSide,
                                             String requiredSide) {
        return new CutPieceDiff(CutKitDict.DIFF_SIDE_MISMATCH, p.getPieceCode(), part, scannedSide,
                p.getFabricBatchNo(), p.getSizeMark(), 0, 1,
                "左右混放：该 " + CutKitDict.partName(part) + label(scannedSide)
                        + " 应放在" + label(requiredSide));
    }

    private static CutPieceDiff surplus(CutKitScanReq.ScanItem p) {
        return new CutPieceDiff(CutKitDict.DIFF_SURPLUS, p.getPieceCode(),
                p.getPart().trim().toUpperCase(), normalizeSide(p.getSide()),
                p.getFabricBatchNo(), p.getSizeMark(), 0, 1,
                "多余片：清单中没有 " + CutKitDict.partName(p.getPart().trim().toUpperCase())
                        + label(normalizeSide(p.getSide())));
    }

    private static String label(String side) {
        return switch (side) {
            case CutKitDict.SIDE_LEFT -> "（左）";
            case CutKitDict.SIDE_RIGHT -> "（右）";
            default -> "";
        };
    }
}
