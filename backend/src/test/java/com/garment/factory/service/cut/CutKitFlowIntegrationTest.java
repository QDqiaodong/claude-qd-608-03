package com.garment.factory.service.cut;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.garment.factory.common.ConflictException;
import com.garment.factory.domain.CutKitDict;
import com.garment.factory.dto.CutKitActionReq;
import com.garment.factory.dto.CutKitCreateReq;
import com.garment.factory.dto.CutKitManifestReq;
import com.garment.factory.dto.CutKitPieceItemReq;
import com.garment.factory.dto.CutKitScanReq;

/**
 * 裁片套件全链路集成验收：真实 Spring + 数据库事务/行锁/唯一索引，
 * 覆盖正常封包交接接收、缺片/重复片/左右混放/跨批次拦截、拆包留痕、
 * 幂等、并发交接竞争、接收复核失败回滚、异常补齐复核闭环、时间线不可覆盖。
 */
@SpringBootTest
@ActiveProfiles("test")
class CutKitFlowIntegrationTest {

    @Autowired
    private CutKitService service;
    @Autowired
    private com.garment.factory.repository.CutKitEventRepository eventRepository;
    @Autowired
    private com.garment.factory.repository.CutKitIdempotencyRepository idempotencyRepository;
    @Autowired
    private com.garment.factory.repository.CutKitRepository kitRepository;

    // ---------------- helpers ----------------

    private CutKitCreateReq createReq() {
        CutKitCreateReq req = new CutKitCreateReq();
        req.setOrderNo("ORD-IT-" + System.nanoTime());
        req.setCutBatchNo("CUT-IT-1");
        req.setGarmentName("集成测试西装");
        req.setSizeMark("M");
        req.setOperator("陈师傅");
        return req;
    }

    private CutKitPieceItemReq item(String part, String side, String batch, String size, int qty) {
        CutKitPieceItemReq i = new CutKitPieceItemReq();
        i.setPart(part);
        i.setSide(side);
        i.setFabricBatchNo(batch);
        i.setSizeMark(size);
        i.setQuantity(qty);
        return i;
    }

    /** 8 片典型清单 */
    private List<CutKitPieceItemReq> manifest8() {
        return new ArrayList<>(List.of(
                item("FRONT", "LEFT", "B1", "M", 1),
                item("FRONT", "RIGHT", "B1", "M", 1),
                item("BACK", "CENTER", "B1", "M", 1),
                item("SLEEVE", "LEFT", "B1", "M", 1),
                item("SLEEVE", "RIGHT", "B1", "M", 1),
                item("COLLAR", "CENTER", "B1", "M", 1),
                item("LINING", "LEFT", "B1", "M", 1),
                item("LINING", "RIGHT", "B1", "M", 1)));
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

    private List<CutKitScanReq.ScanItem> scans8(String prefix, String batch, String size) {
        List<CutKitScanReq.ScanItem> list = new ArrayList<>();
        list.add(scan(prefix + "01", "FRONT", "LEFT", batch, size));
        list.add(scan(prefix + "02", "FRONT", "RIGHT", batch, size));
        list.add(scan(prefix + "03", "BACK", "CENTER", batch, size));
        list.add(scan(prefix + "04", "SLEEVE", "LEFT", batch, size));
        list.add(scan(prefix + "05", "SLEEVE", "RIGHT", batch, size));
        list.add(scan(prefix + "06", "COLLAR", "CENTER", batch, size));
        list.add(scan(prefix + "07", "LINING", "LEFT", batch, size));
        list.add(scan(prefix + "08", "LINING", "RIGHT", batch, size));
        return list;
    }

    private CutKitScanReq scanReq(String operator, List<CutKitScanReq.ScanItem> pieces) {
        CutKitScanReq req = new CutKitScanReq();
        req.setOperator(operator);
        req.setPieces(pieces);
        return req;
    }

    private CutKitManifestReq manifestReq(List<CutKitPieceItemReq> items, String operator) {
        CutKitManifestReq req = new CutKitManifestReq();
        req.setItems(items);
        req.setOperator(operator);
        return req;
    }

    private Long newSealedKit() {
        Long id = (Long) service.create(createReq()).get("id");
        service.saveManifest(id, manifestReq(manifest8(), "陈师傅"));
        service.scanPieces(id, scanReq("陈师傅", scans8("A", "B1", "M")));
        service.seal(id, action("陈师傅", null, null, null, null));
        return id;
    }

    private Long newHandedOverKit() {
        Long id = newSealedKit();
        CutKitActionReq req = action("交出人甲", "接收人乙", null, null, "REQ-HO-" + id);
        service.handOver(id, req);
        return id;
    }

    private CutKitActionReq action(String operator, String receiverName, String reason, String sealNo,
                                   String requestId) {
        CutKitActionReq a = new CutKitActionReq();
        a.setOperator(operator);
        a.setReceiverName(receiverName);
        a.setReason(reason);
        a.setSealNo(sealNo);
        a.setRequestId(requestId);
        return a;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> timeline(Long id) {
        return (List<Map<String, Object>>) service.timeline(id);
    }

    private long countEvent(Long id, String type) {
        return eventRepository.findByKitIdOrderByIdAsc(id).stream()
                .filter(e -> type.equals(e.getEventType())).count();
    }

    private String status(Long id) {
        return (String) service.detail(id).get("status");
    }

    // ---------------- 1. 正常封包 → 交接 → 接收 ----------------

    @Test
    void happyPathSealHandOverReceive() {
        Long id = newHandedOverKit();
        assertEquals(CutKitDict.STATUS_HANDED_OVER, status(id));

        String sealNo = (String) service.detail(id).get("sealNo");
        assertNotNull(sealNo);

        CutKitActionReq receive = action("接收人乙", null, null, sealNo, "REQ-RC-" + id);
        receive.setPieces(scans8("R", "B1", "M"));
        Map<String, Object> result = service.receive(id, receive);
        assertEquals(CutKitDict.STATUS_RECEIVED, result.get("status"));
        assertEquals("接收人乙", result.get("receivedBy"));
        assertNotNull(result.get("receivedAt"));

        // 时间线包含关键事件且只追加
        List<Map<String, Object>> events = timeline(id);
        List<String> types = events.stream().map(e -> (String) e.get("eventType")).toList();
        assertTrue(types.containsAll(List.of("CREATE", "UPDATE_MANIFEST", "SCAN_PIECE",
                "SEAL", "HAND_OVER", "RECEIVE")));
    }

    // ---------------- 2. 缺片 / 重复片 拦截封包 ----------------

    @Test
    void missingPieceBlocksSeal() {
        Long id = (Long) service.create(createReq()).get("id");
        service.saveManifest(id, manifestReq(manifest8(), "陈师傅"));
        List<CutKitScanReq.ScanItem> scans = scans8("A", "B1", "M");
        scans.remove(3); // 缺左袖
        service.scanPieces(id, scanReq("陈师傅", scans));

        Map<String, Object> validation = service.validate(id);
        assertEquals(false, validation.get("passed"));
        assertAnyDiff(validation, CutKitDict.DIFF_MISSING);

        assertThrows400(() -> service.seal(id, action("陈师傅", null, null, null, null)));
        assertEquals(CutKitDict.STATUS_DRAFT, status(id), "封包失败必须停留在草稿");
        assertTrue(countEvent(id, "VALIDATE") >= 1, "拦截也要在时间线留痕");
    }

    @Test
    void duplicatePieceBlocksSeal() {
        Long id = (Long) service.create(createReq()).get("id");
        service.saveManifest(id, manifestReq(manifest8(), "陈师傅"));
        List<CutKitScanReq.ScanItem> scans = scans8("A", "B1", "M");
        scans.add(scan("A99", "SLEEVE", "LEFT", "B1", "M")); // 不同条码、同部位同侧多一片 → 重复片
        service.scanPieces(id, scanReq("陈师傅", scans));
        assertAnyDiff(service.validate(id), CutKitDict.DIFF_DUPLICATE);
        assertThrows400(() -> service.seal(id, action("陈师傅", null, null, null, null)));
    }

    @Test
    void duplicateBarcodeScanIsIdempotent() {
        Long id = (Long) service.create(createReq()).get("id");
        service.saveManifest(id, manifestReq(manifest8(), "陈师傅"));
        service.scanPieces(id, scanReq("陈师傅", scans8("A", "B1", "M")));
        // 同一条码再扫一次：不重复入账
        Map<String, Object> again = service.scanPieces(id,
                scanReq("陈师傅", List.of(scan("A01", "FRONT", "LEFT", "B1", "M"))));
        assertEquals(8, ((List<?>) again.get("packingScans")).size());
    }

    // ---------------- 3. 左右混放 / 跨面料批次 / 尺码冲突 ----------------

    @Test
    void sideMixBatchAndSizeBlockSeal() {
        Long id = (Long) service.create(createReq()).get("id");
        service.saveManifest(id, manifestReq(manifest8(), "陈师傅"));
        List<CutKitScanReq.ScanItem> scans = scans8("A", "B1", "M");
        // 左袖扫成右袖标签（多一片右袖、缺左袖）
        scans.remove(3);
        scans.add(scan("A88", "SLEEVE", "RIGHT", "B1", "M"));
        service.scanPieces(id, scanReq("陈师傅", scans));
        assertAnyDiff(service.validate(id), CutKitDict.DIFF_SIDE_MISMATCH);

        Long id2 = (Long) service.create(createReq()).get("id");
        service.saveManifest(id2, manifestReq(manifest8(), "陈师傅"));
        List<CutKitScanReq.ScanItem> scans2 = scans8("A", "B1", "M");
        scans2.remove(0);
        scans2.add(scan("A01", "FRONT", "LEFT", "B2", "M")); // 跨面料批次
        service.scanPieces(id2, scanReq("陈师傅", scans2));
        assertAnyDiff(service.validate(id2), CutKitDict.DIFF_BATCH_CONFLICT);

        Long id3 = (Long) service.create(createReq()).get("id");
        service.saveManifest(id3, manifestReq(manifest8(), "陈师傅"));
        List<CutKitScanReq.ScanItem> scans3 = scans8("A", "B1", "M");
        scans3.remove(5);
        scans3.add(scan("A06", "COLLAR", "CENTER", "B1", "L")); // 尺码冲突
        service.scanPieces(id3, scanReq("陈师傅", scans3));
        assertAnyDiff(service.validate(id3), CutKitDict.DIFF_SIZE_CONFLICT);
    }

    // ---------------- 4. 拆包留痕，且已交接不能拆/不能回草稿 ----------------

    @Test
    void unsealKeepsBeforeAfterAndHandedOverCannotUnseal() {
        Long id = newSealedKit();
        // 未填原因拒绝
        assertThrows400(() -> service.unseal(id, action("陈师傅", null, "", null, null)));
        service.unseal(id, action("陈师傅", null, "裁剪时发现里布方向错误，需要重裁", null, null));
        assertEquals(CutKitDict.STATUS_DRAFT, status(id));
        assertNull(service.detail(id).get("sealNo"), "拆包后封签作废");

        List<Map<String, Object>> events = timeline(id);
        Map<String, Object> unsealEvent = events.stream()
                .filter(e -> "UNSEAL".equals(e.get("eventType"))).findFirst().orElseThrow();
        assertEquals("裁剪时发现里布方向错误，需要重裁",
                ((Map<?, ?>) unsealEvent.get("detail")).get("reason"));
        assertNotNull(unsealEvent.get("before"), "拆包前快照必须保留");
        assertNotNull(unsealEvent.get("after"), "拆包后快照必须保留");

        // 已交接不能拆包
        Long id2 = newHandedOverKit();
        assertThrows400(() -> service.unseal(id2, action("陈师傅", null, "想改", null, null)));
        assertEquals(CutKitDict.STATUS_HANDED_OVER, status(id2));
        // 已交接不能改清单/扫码
        assertThrows400(() -> service.saveManifest(id2, manifestReq(manifest8(), "陈师傅")));
    }

    // ---------------- 5. 重复请求幂等 ----------------

    @Test
    void handOverAndReceiveIdempotent() {
        Long id = newSealedKit();
        String reqId = "IDEM-HO-" + id;
        service.handOver(id, action("交出人甲", "接收人乙", null, null, reqId));
        // 同一请求号再打两次：重放首次结果，不产生第二条 HAND_OVER 事件
        Map<String, Object> replay1 = service.handOver(id, action("交出人甲", "接收人乙", null, null, reqId));
        Map<String, Object> replay2 = service.handOver(id, action("交出人甲", "接收人乙", null, null, reqId));
        assertEquals(CutKitDict.STATUS_HANDED_OVER, replay1.get("status"));
        assertEquals(CutKitDict.STATUS_HANDED_OVER, replay2.get("status"));
        assertEquals(1, countEvent(id, "HAND_OVER"), "时间线只能有一条交接");
        assertEquals(1, idempotencyRepository.findAll().stream()
                .filter(r -> r.getKitId().equals(id)).count(), "该套件只应有一条幂等记录");

        String sealNo = (String) service.detail(id).get("sealNo");
        String rcReq = "IDEM-RC-" + id;
        CutKitActionReq rc = action("接收人乙", null, null, sealNo, rcReq);
        rc.setPieces(scans8("R", "B1", "M"));
        service.receive(id, rc);
        service.receive(id, rc);
        assertEquals(1, countEvent(id, "RECEIVE"), "重复接收只成功一次");
        assertEquals(CutKitDict.STATUS_RECEIVED, status(id));
    }

    // ---------------- 6. 并发交接竞争：两人同时交接只有一次成功 ----------------

    @Test
    void concurrentHandOverOnlyOneSucceeds() throws Exception {
        int kits = 8;
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < kits; i++) {
            ids.add(newSealedKit());
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger otherError = new AtomicInteger();
        List<Runnable> tasks = new ArrayList<>();
        for (Long id : ids) {
            // 同套件两个人用不同请求号同时交接
            for (int person = 0; person < 2; person++) {
                String personName = person == 0 ? "交出人甲" : "交出人丙";
                String reqId = "CONC-" + id + "-" + person;
                tasks.add(() -> {
                    try {
                        start.await();
                        service.handOver(id, action(personName, "接收人乙", null, null, reqId));
                        success.incrementAndGet();
                    } catch (ConflictException e) {
                        conflict.incrementAndGet();
                    } catch (Exception e) {
                        otherError.incrementAndGet();
                    }
                });
            }
        }
        for (Runnable t : tasks) {
            pool.submit(t);
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(kits, success.get(), "每个套件恰好一次成功");
        assertEquals(kits, conflict.get(), "每个套件恰好一次竞争失败");
        assertEquals(0, otherError.get(), "不应出现半条记录/其他错误");
        for (Long id : ids) {
            assertEquals(CutKitDict.STATUS_HANDED_OVER, status(id));
            assertEquals(1, countEvent(id, "HAND_OVER"), "失败方不能留下半条交接记录");
        }
    }

    // ---------------- 7. 接收复核失败回滚：封签错 / 漏片 / 数量不符 ----------------

    @Test
    void receiveRollbackOnSealMismatchAndMissingPiece() {
        // 7a 封签不符：状态保持已交接，RECEIVE_FAIL 留痕，无幂等成功记录
        Long id = newHandedOverKit();
        CutKitActionReq wrongSeal = action("接收人乙", null, null, "WRONG-SEAL", "RC-WRONG-SEAL");
        wrongSeal.setPieces(scans8("R", "B1", "M"));
        assertThrows409(() -> service.receive(id, wrongSeal));
        assertEquals(CutKitDict.STATUS_HANDED_OVER, status(id));
        assertTrue(countEvent(id, "RECEIVE_FAIL") >= 1, "封签失败要留痕");

        // 7b 漏一片：回滚，状态保持已交接，差异写入失败事件
        CutKitActionReq missing = action("接收人乙", null, null,
                (String) service.detail(id).get("sealNo"), "RC-MISS");
        List<CutKitScanReq.ScanItem> scans = scans8("R", "B1", "M");
        scans.remove(7);
        missing.setPieces(scans);
        assertThrows409(() -> service.receive(id, missing));
        assertEquals(CutKitDict.STATUS_HANDED_OVER, status(id), "漏片不能改成错误状态");
        long failEvents = countEvent(id, "RECEIVE_FAIL");
        assertTrue(failEvents >= 2, "每次失败都留痕");
        Map<String, Object> lastFail = timeline(id).stream()
                .filter(e -> "RECEIVE_FAIL".equals(e.get("eventType")))
                .reduce((a, b) -> b).orElseThrow();
        assertNotNull(((Map<?, ?>) lastFail.get("detail")).get("diffs"));

        // 失败重试用新请求号补齐后仍能正常接收（没有被错误状态卡死）
        CutKitActionReq ok = action("接收人乙", null, null,
                (String) service.detail(id).get("sealNo"), "RC-OK");
        ok.setPieces(scans8("R", "B1", "M"));
        service.receive(id, ok);
        assertEquals(CutKitDict.STATUS_RECEIVED, status(id));
        assertEquals(1, countEvent(id, "RECEIVE"));
    }

    // ---------------- 7b. 并发接收竞争：两人同时接收只有一次成功 ----------------

    @Test
    void concurrentReceiveOnlyOneSucceeds() throws Exception {
        Long id = newHandedOverKit();
        String sealNo = (String) service.detail(id).get("sealNo");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger otherError = new AtomicInteger();
        for (int person = 0; person < 4; person++) {
            final int p = person;
            String reqId = "CONC-RC-" + id + "-" + person;
            pool.submit(() -> {
                try {
                    start.await();
                    CutKitActionReq rc = action("接收人" + p, null, null, sealNo, reqId);
                    rc.setPieces(scans8("RC" + p, "B1", "M"));
                    service.receive(id, rc);
                    success.incrementAndGet();
                } catch (ConflictException e) {
                    conflict.incrementAndGet();
                } catch (Exception e) {
                    otherError.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(1, success.get());
        assertEquals(3, conflict.get());
        assertEquals(0, otherError.get());
        assertEquals(CutKitDict.STATUS_RECEIVED, status(id));
        assertEquals(1, countEvent(id, "RECEIVE"), "接收记录只能有一条");
    }

    // ---------------- 8. 异常：登记 → 补扫 → 复核关闭 → 再接收 ----------------

    @Test
    void exceptionRaiseResolveThenReceive() {
        Long id = newHandedOverKit();
        // 缺原因拒绝
        assertThrows400(() -> service.raiseException(id, action("接收人乙", null, "  ", null, null)));
        service.raiseException(id, action("接收人乙", null, "清点发现里布缺一片", null, null));
        assertEquals(CutKitDict.STATUS_EXCEPTION, status(id));
        assertNotNull(service.detail(id).get("exceptionNo"));

        // 异常态不能直接接收
        CutKitActionReq rc = action("接收人乙", null, null,
                (String) service.detail(id).get("sealNo"), null);
        rc.setPieces(scans8("R", "B1", "M"));
        assertThrows409(() -> service.receive(id, rc));

        // 未补扫不能复核
        assertThrows400(() -> service.resolveReview(id, action("复核人丁", null, "已补齐", null, null)));
        // 补扫缺一片：复核不通过
        List<CutKitScanReq.ScanItem> partial = scans8("X", "B1", "M");
        partial.remove(6);
        service.resolveScans(id, scanReq("处理人丙", partial));
        assertThrows400(() -> service.resolveReview(id, action("复核人丁", null, "已补齐", null, null)));
        assertEquals(CutKitDict.STATUS_EXCEPTION, status(id));

        // 补齐整轮重扫，复核通过回到已交接
        service.resolveScans(id, scanReq("处理人丙", scans8("X", "B1", "M")));
        assertThrows400(() -> service.resolveReview(id, action("复核人丁", null, " ", null, null)));
        service.resolveReview(id, action("复核人丁", null, "缺片已补齐并逐片复核一致", null, null));
        assertEquals(CutKitDict.STATUS_HANDED_OVER, status(id));

        // 异常单已关闭且记录完整
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> exceptions = (List<Map<String, Object>>) service.detail(id).get("exceptions");
        Map<String, Object> ex = exceptions.get(0);
        assertEquals("RESOLVED", ex.get("status"));
        assertEquals("复核人丁", ex.get("resolvedBy"));

        // 回到已交接后正常接收
        CutKitActionReq ok = action("接收人乙", null, null,
                (String) service.detail(id).get("sealNo"), "RC-AFTER-EX");
        ok.setPieces(scans8("R", "B1", "M"));
        service.receive(id, ok);
        assertEquals(CutKitDict.STATUS_RECEIVED, status(id));
        assertTrue(countEvent(id, "RAISE_EXCEPTION") >= 1);
        assertTrue(countEvent(id, "RESOLVE_EXCEPTION") >= 1);
    }

    // ---------------- 9. 时间线只追加不可覆盖 ----------------

    @Test
    void timelineIsAppendOnly() {
        Long id = newSealedKit();
        int beforeSeal = timeline(id).size();
        service.unseal(id, action("陈师傅", null, "留痕测试", null, null));
        service.saveManifest(id, manifestReq(manifest8(), "陈师傅"));
        service.scanPieces(id, scanReq("陈师傅", scans8("A", "B1", "M")));
        service.seal(id, action("陈师傅", null, null, null, null));
        List<Map<String, Object>> events = timeline(id);
        assertTrue(events.size() > beforeSeal);
        // 事件 id 严格递增、操作人齐全
        long prevId = 0;
        for (Map<String, Object> e : events) {
            long eid = (Long) e.get("id");
            assertTrue(eid > prevId);
            prevId = eid;
            assertNotNull(e.get("operator"));
            assertNotNull(e.get("occurredAt"));
        }
    }

    // ---------------- 10. 已接收不能再改状态 ----------------

    @Test
    void receivedKitIsTerminal() {
        Long id = newHandedOverKit();
        CutKitActionReq rc = action("接收人乙", null, null,
                (String) service.detail(id).get("sealNo"), null);
        rc.setPieces(scans8("R", "B1", "M"));
        service.receive(id, rc);
        assertThrows409(() -> service.handOver(id, action("甲", "乙", null, null, "X")));
        assertThrows400(() -> service.unseal(id, action("甲", null, "r", null, null)));
    }

    // ---------------- assertions ----------------

    @SuppressWarnings("unchecked")
    private void assertAnyDiff(Map<String, Object> validation, String diffType) {
        List<com.garment.factory.dto.CutPieceDiff> diffs =
                (List<com.garment.factory.dto.CutPieceDiff>) validation.get("diffs");
        assertTrue(diffs.stream().anyMatch(d -> diffType.equals(d.getType())),
                "应包含差异类型 " + diffType + "，实际：" + diffs);
    }

    private void assertThrows400(Runnable r) {
        try {
            r.run();
            throw new AssertionError("应抛出业务校验异常");
        } catch (ConflictException e) {
            throw new AssertionError("预期 400 业务校验异常，实际是 409 冲突：" + e.getMessage());
        } catch (com.garment.factory.common.BizException e) {
            assertNotNull(e.getMessage());
        }
    }

    private void assertThrows409(Runnable r) {
        try {
            r.run();
            throw new AssertionError("应抛出冲突异常");
        } catch (ConflictException e) {
            // expected
        }
    }
}
