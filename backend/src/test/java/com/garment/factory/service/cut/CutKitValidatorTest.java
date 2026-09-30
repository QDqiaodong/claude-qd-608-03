package com.garment.factory.service.cut;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.garment.factory.domain.CutKitDict;
import com.garment.factory.dto.CutKitPieceItemReq;
import com.garment.factory.dto.CutKitScanReq;
import com.garment.factory.dto.CutPieceDiff;

/**
 * 裁片核对引擎验收用例：缺片、重复片、左右混放、跨面料批次、尺码冲突、完全一致、200 片性能。
 */
class CutKitValidatorTest {

    private CutKitPieceItemReq manifestItem(String part, String side, String batch, String size, int qty) {
        CutKitPieceItemReq r = new CutKitPieceItemReq();
        r.setPart(part);
        r.setSide(side);
        r.setFabricBatchNo(batch);
        r.setSizeMark(size);
        r.setQuantity(qty);
        return r;
    }

    private CutKitScanReq.ScanItem scan(String code, String part, String side, String batch, String size) {
        CutKitScanReq.ScanItem s = new CutKitScanReq.ScanItem();
        s.setPieceCode(code);
        s.setPart(part);
        s.setSide(side);
        s.setFabricBatchNo(batch);
        s.setSizeMark(size);
        return s;
    }

    /** 一套典型西装清单：前片左右各一、后片中片一、袖片左右各一、领片一、里布左右各一 = 8 片 */
    private List<CutKitPieceItemReq> suitManifest() {
        List<CutKitPieceItemReq> m = new ArrayList<>();
        m.add(manifestItem("FRONT", "LEFT", "B1", "M", 1));
        m.add(manifestItem("FRONT", "RIGHT", "B1", "M", 1));
        m.add(manifestItem("BACK", "CENTER", "B1", "M", 1));
        m.add(manifestItem("SLEEVE", "LEFT", "B1", "M", 1));
        m.add(manifestItem("SLEEVE", "RIGHT", "B1", "M", 1));
        m.add(manifestItem("COLLAR", "CENTER", "B1", "M", 1));
        m.add(manifestItem("LINING", "LEFT", "B1", "M", 1));
        m.add(manifestItem("LINING", "RIGHT", "B1", "M", 1));
        return m;
    }

    private List<CutKitScanReq.ScanItem> suitScans() {
        List<CutKitScanReq.ScanItem> s = new ArrayList<>();
        s.add(scan("P01", "FRONT", "LEFT", "B1", "M"));
        s.add(scan("P02", "FRONT", "RIGHT", "B1", "M"));
        s.add(scan("P03", "BACK", "CENTER", "B1", "M"));
        s.add(scan("P04", "SLEEVE", "LEFT", "B1", "M"));
        s.add(scan("P05", "SLEEVE", "RIGHT", "B1", "M"));
        s.add(scan("P06", "COLLAR", "CENTER", "B1", "M"));
        s.add(scan("P07", "LINING", "LEFT", "B1", "M"));
        s.add(scan("P08", "LINING", "RIGHT", "B1", "M"));
        return s;
    }

    @Test
    void exactMatchHasNoDiffs() {
        List<CutPieceDiff> diffs = CutKitValidator.compare(suitManifest(), suitScans());
        assertTrue(diffs.isEmpty(), () -> "完全一致应无差异，实际：" + diffs);
    }

    @Test
    void missingPieceDetected() {
        List<CutKitScanReq.ScanItem> scans = suitScans();
        scans.removeIf(s -> "P04".equals(s.getPieceCode())); // 缺左袖
        List<CutPieceDiff> diffs = CutKitValidator.compare(suitManifest(), scans);
        assertEquals(1, diffs.size());
        assertEquals(CutKitDict.DIFF_MISSING, diffs.get(0).getType());
        assertEquals("SLEEVE", diffs.get(0).getPart());
        assertEquals("LEFT", diffs.get(0).getSide());
        assertEquals(1, diffs.get(0).getExpectedQty());
        assertEquals(0, diffs.get(0).getActualQty());
    }

    @Test
    void duplicateBarcodeDetected() {
        List<CutKitScanReq.ScanItem> scans = suitScans();
        scans.add(scan("P04", "SLEEVE", "LEFT", "B1", "M")); // 同一条码再扫一次
        List<CutPieceDiff> diffs = CutKitValidator.compare(suitManifest(), scans);
        assertTrue(diffs.stream().anyMatch(d -> CutKitDict.DIFF_DUPLICATE.equals(d.getType())
                && "P04".equals(d.getPieceCode())));
        assertFalse(diffs.isEmpty());
    }

    @Test
    void duplicateGroupDetected() {
        // 左袖清单 1 片，实际扫两片不同条码 → 重复片
        List<CutKitScanReq.ScanItem> scans = suitScans();
        scans.add(scan("P99", "SLEEVE", "LEFT", "B1", "M"));
        List<CutPieceDiff> diffs = CutKitValidator.compare(suitManifest(), scans);
        assertTrue(diffs.stream().anyMatch(d -> CutKitDict.DIFF_DUPLICATE.equals(d.getType())
                && "SLEEVE".equals(d.getPart()) && "LEFT".equals(d.getSide())
                && d.getActualQty() == 2));
    }

    @Test
    void sideSwapDetectedAsMix() {
        // 左袖没扫到，反而扫了两片右袖：多出的一片右袖跨侧填补左袖位 → 定性左右混放（不重复报缺片/多余片）
        List<CutKitScanReq.ScanItem> scans = suitScans();
        scans.removeIf(s -> "P04".equals(s.getPieceCode()));
        scans.add(scan("P88", "SLEEVE", "RIGHT", "B1", "M"));
        List<CutPieceDiff> diffs = CutKitValidator.compare(suitManifest(), scans);
        assertEquals(1, diffs.size(), "一片跨侧混放只产生一条差异：" + diffs);
        assertEquals(CutKitDict.DIFF_SIDE_MISMATCH, diffs.get(0).getType());
        assertEquals("P88", diffs.get(0).getPieceCode());
        assertTrue(diffs.get(0).getMessage().contains("左"));
    }

    @Test
    void sideMixAndMissingBothReported() {
        // 左袖 2 片、右袖 1 片；实际一片左袖都没有，右袖扫了 2 片：
        // 1 片右袖跨侧填补左袖（混放），左袖仍欠 1 片（缺片），两类问题各报一次
        List<CutKitPieceItemReq> manifest = suitManifest();
        manifest.removeIf(m -> "SLEEVE".equals(m.getPart()));
        manifest.add(manifestItem("SLEEVE", "LEFT", "B1", "M", 2));
        manifest.add(manifestItem("SLEEVE", "RIGHT", "B1", "M", 1));
        List<CutKitScanReq.ScanItem> scans = suitScans();
        scans.removeIf(s -> "SLEEVE".equals(s.getPart()));
        scans.add(scan("P05", "SLEEVE", "RIGHT", "B1", "M"));
        scans.add(scan("P88", "SLEEVE", "RIGHT", "B1", "M"));

        List<CutPieceDiff> diffs = CutKitValidator.compare(manifest, scans);
        assertEquals(1, diffs.stream().filter(d -> CutKitDict.DIFF_SIDE_MISMATCH.equals(d.getType())).count(),
                "一片跨侧混放：" + diffs);
        assertEquals(1, diffs.stream().filter(d -> CutKitDict.DIFF_MISSING.equals(d.getType())
                && "LEFT".equals(d.getSide())).count(), "左袖填补后仍缺 1 片：" + diffs);
        assertFalse(diffs.stream().anyMatch(d -> CutKitDict.DIFF_SURPLUS.equals(d.getType())));
    }

    @Test
    void oneSideEntirelyMissingIsMissingNotMix() {
        // 左右各一应扫，实际只扫到一片右袖：右袖位齐、左袖缺片（标签即右片，不算混放）
        List<CutKitScanReq.ScanItem> scans = suitScans();
        scans.removeIf(s -> "P04".equals(s.getPieceCode()) || "P05".equals(s.getPieceCode()));
        scans.add(scan("P88", "SLEEVE", "RIGHT", "B1", "M"));
        List<CutPieceDiff> diffs = CutKitValidator.compare(suitManifest(), scans);
        assertEquals(1, diffs.size());
        assertEquals(CutKitDict.DIFF_MISSING, diffs.get(0).getType());
        assertEquals("LEFT", diffs.get(0).getSide());
    }

    @Test
    void fabricBatchConflictDetected() {
        List<CutKitScanReq.ScanItem> scans = suitScans();
        scans.removeIf(s -> "P01".equals(s.getPieceCode()));
        scans.add(scan("P01", "FRONT", "LEFT", "B2", "M")); // 混入别的面料批次
        List<CutPieceDiff> diffs = CutKitValidator.compare(suitManifest(), scans);
        assertEquals(1, diffs.size());
        assertEquals(CutKitDict.DIFF_BATCH_CONFLICT, diffs.get(0).getType());
        assertEquals("B2", diffs.get(0).getFabricBatchNo());
    }

    @Test
    void sizeMarkConflictDetected() {
        List<CutKitScanReq.ScanItem> scans = suitScans();
        scans.removeIf(s -> "P06".equals(s.getPieceCode()));
        scans.add(scan("P06", "COLLAR", "CENTER", "B1", "L")); // 尺码标记不符
        List<CutPieceDiff> diffs = CutKitValidator.compare(suitManifest(), scans);
        assertEquals(1, diffs.size());
        assertEquals(CutKitDict.DIFF_SIZE_CONFLICT, diffs.get(0).getType());
        assertEquals("L", diffs.get(0).getSizeMark());
    }

    @Test
    void surplusPieceDetected() {
        List<CutKitScanReq.ScanItem> scans = suitScans();
        scans.add(scan("P77", "POCKET", "CENTER", "B1", "M")); // 清单没有袋片
        List<CutPieceDiff> diffs = CutKitValidator.compare(suitManifest(), scans);
        assertEquals(1, diffs.size());
        assertEquals(CutKitDict.DIFF_SURPLUS, diffs.get(0).getType());
        assertEquals("P77", diffs.get(0).getPieceCode());
    }

    @Test
    void manifestSelfConflictRejected() {
        List<CutKitPieceItemReq> m = suitManifest();
        // 同(部位,侧)两行登记了不同批次 → 清单自身不自洽，建档即拦
        m.add(manifestItem("FRONT", "LEFT", "B9", "M", 1));
        try {
            CutKitValidator.indexManifest(m);
            throw new AssertionError("清单批次冲突应被拒绝");
        } catch (com.garment.factory.common.BizException expected) {
            assertTrue(expected.getMessage().contains("面料批次"));
        }
    }

    @Test
    void twoHundredPiecesValidationIsFast() {
        // 200 片清单：10 种部位 × 左右各 10 片
        List<CutKitPieceItemReq> manifest = new ArrayList<>();
        String[] parts = {"FRONT", "BACK", "SLEEVE", "COLLAR", "LINING", "POCKET", "CUFF",
                "PLACKET", "INTERLINING", "OTHER"};
        int code = 1;
        for (String part : parts) {
            manifest.add(manifestItem(part, "LEFT", "B1", "M", 10));
            manifest.add(manifestItem(part, "RIGHT", "B1", "M", 10));
        }
        List<CutKitScanReq.ScanItem> scans = new ArrayList<>();
        for (String part : parts) {
            for (String side : new String[]{"LEFT", "RIGHT"}) {
                for (int i = 0; i < 10; i++) {
                    scans.add(scan("C" + String.format("%03d", code++), part, side, "B1", "M"));
                }
            }
        }
        assertEquals(200, scans.size());

        long start = System.nanoTime();
        List<CutPieceDiff> ok = CutKitValidator.compare(manifest, scans);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(ok.isEmpty(), "200 片完全一致时不应有差异");
        assertTrue(elapsedMs < 1000, "200 片校验应在 1 秒内完成，实际 " + elapsedMs + "ms");

        // 抽掉 3 片 + 混入 1 个错误批次，差异也能在 1 秒内全部识别
        scans.remove(0);
        scans.remove(0);
        scans.remove(0);
        scans.add(scan("C901", parts[0], "LEFT", "B2", "M"));
        start = System.nanoTime();
        List<CutPieceDiff> bad = CutKitValidator.compare(manifest, scans);
        elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertFalse(bad.isEmpty());
        assertTrue(elapsedMs < 1000, "200 片带差异校验应在 1 秒内完成，实际 " + elapsedMs + "ms");
    }
}
