package com.garment.factory.service.cut;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.garment.factory.dto.CutKitActionReq;
import com.garment.factory.dto.CutKitCreateReq;
import com.garment.factory.dto.CutKitManifestReq;
import com.garment.factory.dto.CutKitPieceItemReq;
import com.garment.factory.dto.CutKitScanReq;

/**
 * 两百片裁片批量校验的真实数据库端到端性能：建档清单 → 200 片扫码落库 →
 * 服务端全量校验 → 封包，整链路应在 3 秒内（演示库无网络抖动时应远低于此）。
 */
@SpringBootTest
@ActiveProfiles("test")
class CutKitLargeBatchPerformanceTest {

    @Autowired
    private CutKitService service;

    private CutKitPieceItemReq item(String part, String side, int qty) {
        CutKitPieceItemReq i = new CutKitPieceItemReq();
        i.setPart(part);
        i.setSide(side);
        i.setFabricBatchNo("FB3001-A");
        i.setSizeMark("M");
        i.setQuantity(qty);
        return i;
    }

    private CutKitScanReq.ScanItem scan(String code, String part, String side) {
        CutKitScanReq.ScanItem s = new CutKitScanReq.ScanItem();
        s.setPieceCode(code);
        s.setPart(part);
        s.setSide(side);
        s.setFabricBatchNo("FB3001-A");
        s.setSizeMark("M");
        return s;
    }

    @Test
    void twoHundredPiecesEndToEnd() {
        CutKitCreateReq create = new CutKitCreateReq();
        create.setOrderNo("ORD-BIG-200");
        create.setCutBatchNo("CUT-BIG");
        create.setGarmentName("两百片批量套装");
        create.setSizeMark("M");
        create.setOperator("陈师傅");
        Long id = (Long) service.create(create).get("id");

        // 20 个(部位,侧)组，每组 10 片 = 200 片
        String[] parts = {"FRONT", "BACK", "SLEEVE", "COLLAR", "LINING", "POCKET", "CUFF",
                "PLACKET", "INTERLINING", "OTHER"};
        List<CutKitPieceItemReq> manifest = new ArrayList<>();
        for (String part : parts) {
            manifest.add(item(part, "LEFT", 10));
            manifest.add(item(part, "RIGHT", 10));
        }
        CutKitManifestReq mr = new CutKitManifestReq();
        mr.setItems(manifest);
        mr.setOperator("陈师傅");
        service.saveManifest(id, mr);

        List<CutKitScanReq.ScanItem> pieces = new ArrayList<>();
        int seq = 1;
        for (String part : parts) {
            for (String side : new String[]{"LEFT", "RIGHT"}) {
                for (int i = 0; i < 10; i++) {
                    pieces.add(scan("B" + String.format("%04d", seq++), part, side));
                }
            }
        }
        assertEquals(200, pieces.size());

        long t0 = System.currentTimeMillis();
        CutKitScanReq scanReq = new CutKitScanReq();
        scanReq.setOperator("陈师傅");
        scanReq.setPieces(pieces);
        service.scanPieces(id, scanReq);
        Map<String, Object> validation = service.validate(id);
        long t1 = System.currentTimeMillis();

        assertEquals(true, validation.get("passed"), () -> "200 片应完全一致：" + validation.get("diffs"));
        assertEquals(200, validation.get("expectedTotal"));
        assertEquals(200, validation.get("actualTotal"));
        assertTrue(t1 - t0 < 3000, "200 片扫码落库+全量校验应在 3s 内，实际 " + (t1 - t0) + "ms");

        CutKitActionReq seal = new CutKitActionReq();
        seal.setOperator("陈师傅");
        long t2 = System.currentTimeMillis();
        service.seal(id, seal);
        long t3 = System.currentTimeMillis();
        assertTrue(t3 - t2 < 2000, "200 片封包应在 2s 内，实际 " + (t3 - t2) + "ms");
        assertEquals("SEALED", service.detail(id).get("status"));
    }
}
