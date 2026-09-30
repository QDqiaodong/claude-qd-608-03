package com.garment.factory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.garment.factory.common.BizException;
import com.garment.factory.common.ConflictException;
import com.garment.factory.domain.CustomOrder;
import com.garment.factory.domain.Customer;
import com.garment.factory.domain.Fabric;
import com.garment.factory.domain.Measurement;
import com.garment.factory.domain.OrderMeasurementSnapshot;
import com.garment.factory.domain.Style;
import com.garment.factory.dto.MeasurementConfirmReq;
import com.garment.factory.dto.MeasurementSaveReq;
import com.garment.factory.dto.OrderCreateReq;
import com.garment.factory.dto.OrderDetailDto;
import com.garment.factory.dto.OrderMeasurementView;
import com.garment.factory.repository.CustomOrderRepository;
import com.garment.factory.repository.CustomerRepository;
import com.garment.factory.repository.FabricRepository;
import com.garment.factory.repository.MeasurementRepository;
import com.garment.factory.repository.OrderMeasurementSnapshotRepository;
import com.garment.factory.repository.StyleRepository;

/**
 * 订单量体快照全链路验收（真实 Spring + H2 事务/行锁/唯一索引）：
 * 新单冻结、后续量体编辑不回写、使用中记录删除拦截、已交付订单保留历史、
 * 缺失旧记录待核对、旧数据补齐幂等且不覆盖人工确认、下单/编辑/删除并发一致性。
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderMeasurementSnapshotIntegrationTest {

    @Autowired
    private OrderService orderService;
    @Autowired
    private MeasurementService measurementService;
    @Autowired
    private OrderSnapshotService snapshotService;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private StyleRepository styleRepository;
    @Autowired
    private FabricRepository fabricRepository;
    @Autowired
    private MeasurementRepository measurementRepository;
    @Autowired
    private CustomOrderRepository orderRepository;
    @Autowired
    private OrderMeasurementSnapshotRepository snapshotRepository;

    private Customer customer;
    private Style style;
    private Fabric fabric;
    private long seq;

    @BeforeEach
    void setUp() {
        seq = System.nanoTime();
        customer = new Customer();
        customer.setCustomerNo("KH-T" + seq);
        customer.setName("测试客户");
        customer.setPhone("139" + (seq % 100000000L));
        customer.setGender("女");
        customer.setVipLevel("NORMAL");
        customer = customerRepository.save(customer);

        style = new Style();
        style.setStyleNo("ST-T" + seq);
        style.setName("测试西装");
        style.setCategory("SUIT");
        style.setBasePrice(new BigDecimal("1000.00"));
        style.setCraftLevel(2);
        style.setOnSale(Boolean.TRUE);
        style = styleRepository.save(style);

        fabric = new Fabric();
        fabric.setFabricNo("FB-T" + seq);
        fabric.setName("测试面料");
        fabric.setComposition("WOOL");
        fabric.setColor("藏青");
        fabric.setColorHex("#000000");
        fabric.setStockMeters(new BigDecimal("999.00"));
        fabric.setUnitPrice(new BigDecimal("100.00"));
        fabric.setSupplier("测试供应商");
        fabric = fabricRepository.save(fabric);
    }

    private MeasurementSaveReq msrReq(String no, BigDecimal bust) {
        MeasurementSaveReq req = new MeasurementSaveReq();
        req.setRecordNo(no);
        req.setCustomerId(customer.getId());
        req.setTailorName("方绮");
        req.setMeasureDate(LocalDate.now().minusDays(2));
        req.setBust(bust);
        req.setWaist(new BigDecimal("68.00"));
        req.setHip(new BigDecimal("92.00"));
        req.setShoulder(new BigDecimal("38.00"));
        req.setSleeveLength(new BigDecimal("57.00"));
        req.setBodyLength(new BigDecimal("62.00"));
        req.setPantsLength(new BigDecimal("96.00"));
        req.setRemark("原始备注");
        return req;
    }

    private Measurement measurement(String no, BigDecimal bust) {
        return measurementService.createForOrder(msrReq(no, bust), customer);
    }

    private OrderCreateReq orderReq(Long measurementId, Long expectedVersion) {
        OrderCreateReq req = new OrderCreateReq();
        req.setCustomerId(customer.getId());
        req.setStyleId(style.getId());
        req.setFabricId(fabric.getId());
        req.setMeasurementId(measurementId);
        req.setExpectedMeasurementVersion(expectedVersion);
        req.setUsageMeters(new BigDecimal("3.00"));
        req.setDeliveryDate(LocalDate.now().plusDays(10));
        req.setPayDeposit(Boolean.TRUE);
        req.setRemark("测试订单");
        return req;
    }

    /** 1. 新订单冻结 + 后续量体编辑不回写，看板/详情口径一致 */
    @Test
    void newOrderFreezesSnapshot_andLaterEditDoesNotWriteBack() {
        Measurement m = measurement("ME-T1-" + seq, new BigDecimal("86.00"));
        CustomOrder order = orderService.create(orderReq(m.getId(), m.getVersion()));

        OrderMeasurementSnapshot snapshot = snapshotRepository.findByOrderId(order.getId()).orElseThrow();
        assertEquals(OrderMeasurementSnapshot.STATUS_FROZEN, snapshot.getStatus());
        assertEquals(0, new BigDecimal("86.00").compareTo(snapshot.getBust()));
        assertEquals("方绮", snapshot.getTailorName());
        assertEquals("原始备注", snapshot.getRemark());
        assertEquals(m.getId(), snapshot.getMeasurementId());

        // 编辑量体记录（允许维护新值）
        MeasurementSaveReq edit = msrReq(m.getRecordNo(), new BigDecimal("91.50"));
        edit.setId(m.getId());
        edit.setRemark("改成新尺寸");
        Measurement edited = measurementService.save(edit);
        assertEquals(1L, edited.getVersion());

        // 快照不动
        snapshot = snapshotRepository.findByOrderId(order.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("86.00").compareTo(snapshot.getBust()));
        assertEquals("原始备注", snapshot.getRemark());

        // 详情口径（详情会惰性补齐，这里本来就有快照）
        OrderDetailDto detail = orderService.detail(order.getId());
        OrderMeasurementView view = detail.getMeasurement();
        assertTrue(view.getFrozen());
        assertFalse(view.getPending());
        assertEquals(0, new BigDecimal("86.00").compareTo(view.getBust()));

        // 看板卡片同一口径
        Map<String, Object> board = orderService.board();
        @SuppressWarnings("unchecked")
        OrderMeasurementView boardView = findCard(board, order.getId()).getMeasurement();
        assertEquals(0, new BigDecimal("86.00").compareTo(boardView.getBust()));
        assertTrue(boardView.getFrozen());
    }

    /** 2. 量体记录被未交付订单引用：删除被服务端拒绝并说明阻断来源 */
    @Test
    void deleteBlockedByActiveOrder_withBlockingSource() {
        Measurement m = measurement("ME-T2-" + seq, new BigDecimal("86.00"));
        CustomOrder order = orderService.create(orderReq(m.getId(), m.getVersion()));

        BizException ex = assertThrows(BizException.class, () -> measurementService.remove(m.getId()));
        assertTrue(ex.getMessage().contains("未交付订单"));
        assertTrue(ex.getMessage().contains(order.getOrderNo()));
        assertTrue(measurementRepository.findById(m.getId()).isPresent());
    }

    /** 3. 只被已交付订单引用的记录可以删除，历史订单仍完整展示原尺寸与来源 */
    @Test
    void deliveredOnlyRecordCanBeDeleted_butHistoryKeepsFrozenSizes() {
        Measurement m = measurement("ME-T3-" + seq, new BigDecimal("88.00"));
        CustomOrder delivered = new LegacyOrderBuilder()
                .measurement(m).status("DELIVERED").buildAndSave();
        snapshotService.ensureSnapshot(delivered);

        // 没有未交付订单引用：删除放行
        measurementService.remove(m.getId());
        assertFalse(measurementRepository.findById(m.getId()).isPresent());

        OrderMeasurementSnapshot snapshot = snapshotRepository.findByOrderId(delivered.getId()).orElseThrow();
        assertEquals(OrderMeasurementSnapshot.STATUS_FROZEN, snapshot.getStatus());
        assertEquals(0, new BigDecimal("88.00").compareTo(snapshot.getBust()));
        assertEquals(m.getId(), snapshot.getMeasurementId());

        OrderMeasurementView view = orderService.detail(delivered.getId()).getMeasurement();
        assertTrue(view.getFrozen());
        assertEquals(0, new BigDecimal("88.00").compareTo(view.getBust()));
    }

    /** 4. 旧订单无快照但原记录在：补齐冻结；重复执行幂等，只形成一份 */
    @Test
    void legacyOrderBackfill_isIdempotent() {
        Measurement m = measurement("ME-T4-" + seq, new BigDecimal("84.00"));
        CustomOrder legacy = new LegacyOrderBuilder().measurement(m).status("CUT").buildAndSave();

        assertTrue(snapshotRepository.findByOrderId(legacy.getId()).isEmpty());
        OrderMeasurementSnapshot first = snapshotService.ensureSnapshot(
                orderRepository.findById(legacy.getId()).orElseThrow());
        assertEquals(OrderMeasurementSnapshot.STATUS_FROZEN, first.getStatus());
        long idAfterFirst = first.getId();

        // 重复执行（含并发重复）只形成一份最终快照
        Runnable backfill = () -> snapshotService.ensureSnapshot(
                orderRepository.findById(legacy.getId()).orElseThrow());
        runConcurrent(8, backfill);

        List<OrderMeasurementSnapshot> rows = snapshotRepository.findAll().stream()
                .filter(s -> s.getOrderId().equals(legacy.getId())).toList();
        assertEquals(1, rows.size());
        assertEquals(idAfterFirst, rows.get(0).getId());
    }

    /** 5. 原量体记录缺失：标待核对不伪造；人工确认后自动补齐不覆盖，重复确认给可理解提示 */
    @Test
    void missingRecordMarksPending_manualConfirmWinsOverBackfill() {
        CustomOrder legacy = new LegacyOrderBuilder().missingMeasurement(999001L).status("DELIVERED")
                .buildAndSave();

        snapshotService.backfillAll();
        OrderMeasurementSnapshot snapshot = snapshotRepository.findByOrderId(legacy.getId()).orElseThrow();
        assertEquals(OrderMeasurementSnapshot.STATUS_MISSING, snapshot.getStatus());
        assertNull(snapshot.getBust());
        assertNull(snapshot.getTailorName());

        OrderMeasurementView view = orderService.detail(legacy.getId()).getMeasurement();
        assertTrue(view.getPending());
        assertNull(view.getBust());

        // 人工核对补录
        MeasurementConfirmReq confirm = new MeasurementConfirmReq();
        confirm.setRecordNo("ME-RECOVER-" + seq);
        confirm.setTailorName("罗予");
        confirm.setMeasureDate(LocalDate.now().minusDays(30));
        confirm.setBust(new BigDecimal("90.00"));
        confirm.setWaist(new BigDecimal("72.00"));
        confirm.setHip(new BigDecimal("96.00"));
        confirm.setShoulder(new BigDecimal("39.00"));
        confirm.setSleeveLength(new BigDecimal("58.00"));
        confirm.setBodyLength(new BigDecimal("65.00"));
        confirm.setPantsLength(new BigDecimal("99.00"));
        confirm.setRemark("档案室翻单核对");
        confirm.setOperator("主管");
        snapshotService.confirmMissing(legacy.getId(), confirm);

        snapshot = snapshotRepository.findByOrderId(legacy.getId()).orElseThrow();
        assertEquals(OrderMeasurementSnapshot.STATUS_MANUAL_CONFIRMED, snapshot.getStatus());
        assertEquals("主管", snapshot.getConfirmedBy());
        assertEquals(0, new BigDecimal("90.00").compareTo(snapshot.getBust()));

        // 再跑自动补齐：不覆盖人工结果
        snapshotService.backfillAll();
        snapshot = snapshotRepository.findByOrderId(legacy.getId()).orElseThrow();
        assertEquals(OrderMeasurementSnapshot.STATUS_MANUAL_CONFIRMED, snapshot.getStatus());
        assertEquals(0, new BigDecimal("90.00").compareTo(snapshot.getBust()));

        // 重复人工确认：409 可理解提示
        ConflictException conflict = assertThrows(ConflictException.class,
                () -> snapshotService.confirmMissing(legacy.getId(), confirm));
        assertTrue(conflict.getMessage().contains("已经人工核对确认"));
    }

    /** 6. 人工确认不能覆盖已冻结的完整快照 */
    @Test
    void confirmRejectedWhenFrozenSnapshotExists() {
        Measurement m = measurement("ME-T6-" + seq, new BigDecimal("86.00"));
        CustomOrder order = orderService.create(orderReq(m.getId(), m.getVersion()));

        MeasurementConfirmReq confirm = new MeasurementConfirmReq();
        confirm.setTailorName("罗予");
        confirm.setMeasureDate(LocalDate.now().minusDays(1));
        confirm.setBust(new BigDecimal("90.00"));
        confirm.setWaist(new BigDecimal("72.00"));
        confirm.setHip(new BigDecimal("96.00"));
        confirm.setShoulder(new BigDecimal("39.00"));
        confirm.setSleeveLength(new BigDecimal("58.00"));
        confirm.setBodyLength(new BigDecimal("65.00"));
        confirm.setPantsLength(new BigDecimal("99.00"));
        confirm.setOperator("主管");
        ConflictException ex = assertThrows(ConflictException.class,
                () -> snapshotService.confirmMissing(order.getId(), confirm));
        assertTrue(ex.getMessage().contains("已有完整冻结尺寸"));
    }

    /** 7. 下单与量体记录并发编辑：版本过期要求重新选择；并发删除：要么成功冻结要么要求重选，无半份快照 */
    @Test
    void concurrentCreateEditDelete_neverLeavesHalfSnapshotOrDanglingRef() throws Exception {
        // 7a 版本过期：向导打开后记录被他人改过
        Measurement m1 = measurement("ME-T7A-" + seq, new BigDecimal("86.00"));
        Long staleVersion = m1.getVersion();
        MeasurementSaveReq edit = msrReq(m1.getRecordNo(), new BigDecimal("90.00"));
        edit.setId(m1.getId());
        measurementService.save(edit);
        ConflictException conflict = assertThrows(ConflictException.class,
                () -> orderService.create(orderReq(m1.getId(), staleVersion)));
        assertTrue(conflict.getMessage().contains("已被他人修改"));
        assertTrue(snapshotRepository.findAll().stream()
                .noneMatch(s -> customer.getId().equals(s.getCustomerId())));

        // 7b 下单与删除并发：结束后每条订单恰好一份完整 FROZEN 快照，或下单方收到重选提示
        Measurement m2 = measurement("ME-T7B-" + seq, new BigDecimal("86.00"));
        Long version = m2.getVersion();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger createOk = new AtomicInteger();
        AtomicInteger createRejected = new AtomicInteger();
        AtomicInteger deleteOk = new AtomicInteger();
        AtomicInteger deleteBlocked = new AtomicInteger();
        List<Throwable> unexpected = new ArrayList<>();

        Runnable createTask = () -> {
            try {
                start.await();
                orderService.create(orderReq(m2.getId(), version));
                createOk.incrementAndGet();
            } catch (ConflictException e) {
                createRejected.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                unexpected.add(e);
            }
        };
        Runnable deleteTask = () -> {
            try {
                start.await();
                measurementService.remove(m2.getId());
                deleteOk.incrementAndGet();
            } catch (ConflictException e) {
                // 记录恰好在并发窗口被删/被改时也可能收到 409，统一计为删除未执行成功
                deleteBlocked.incrementAndGet();
            } catch (BizException e) {
                deleteBlocked.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                unexpected.add(e);
            }
        };

        pool.submit(createTask);
        pool.submit(deleteTask);
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
        assertTrue(unexpected.isEmpty(), "出现未预期异常: " + unexpected);

        if (deleteOk.get() == 1) {
            // 删除先赢：下单必须被拒，且没有订单/快照残留
            assertEquals(0, createOk.get());
            assertEquals(1, createRejected.get());
            assertTrue(snapshotRepository.findAll().stream()
                    .noneMatch(s -> customer.getId().equals(s.getCustomerId())));
            assertTrue(orderRepository.findAll().stream()
                    .noneMatch(o -> m2.getId().equals(o.getMeasurementId())));
        } else {
            // 下单先赢：删除被阻断，订单有且仅有一份完整快照
            assertEquals(1, deleteBlocked.get());
            assertEquals(1, createOk.get());
            CustomOrder saved = orderRepository.findAll().stream()
                    .filter(o -> m2.getId().equals(o.getMeasurementId())).findFirst().orElseThrow();
            List<OrderMeasurementSnapshot> rows = snapshotRepository.findAll().stream()
                    .filter(s -> s.getOrderId().equals(saved.getId())).toList();
            assertEquals(1, rows.size());
            OrderMeasurementSnapshot only = rows.get(0);
            assertEquals(saved.getId(), only.getOrderId());
            assertEquals(OrderMeasurementSnapshot.STATUS_FROZEN, only.getStatus());
            assertNotNull(only.getBust());
            assertNotNull(only.getTailorName());
            assertNotNull(only.getMeasureDate());
            assertNotNull(only.getRemark());
        }
    }

    private void runConcurrent(int threads, Runnable task) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    task.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        try {
            assertTrue(done.await(20, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pool.shutdown();
    }

    @SuppressWarnings("unchecked")
    private com.garment.factory.dto.OrderBoardCardDto findCard(Map<String, Object> board, Long orderId) {
        for (List<com.garment.factory.dto.OrderBoardCardDto> cards :
                ((Map<String, List<com.garment.factory.dto.OrderBoardCardDto>>) board.get("columns")).values()) {
            for (com.garment.factory.dto.OrderBoardCardDto card : cards) {
                if (card.getId().equals(orderId)) {
                    return card;
                }
            }
        }
        throw new AssertionError("看板中未找到订单 " + orderId);
    }

    /** 直接落库一笔历史订单（模拟下单功能上线前已存在、没有快照的订单） */
    private class LegacyOrderBuilder {
        private Measurement measurement;
        private Long missingMeasurementId;
        private String status = "ORDERED";

        LegacyOrderBuilder measurement(Measurement m) {
            this.measurement = m;
            return this;
        }

        LegacyOrderBuilder missingMeasurement(Long id) {
            this.missingMeasurementId = id;
            return this;
        }

        LegacyOrderBuilder status(String status) {
            this.status = status;
            return this;
        }

        CustomOrder buildAndSave() {
            CustomOrder order = new CustomOrder();
            order.setOrderNo("ORDT" + Long.toString(seq, 36).toUpperCase().replace("-", "")
                    + (System.nanoTime() % 100000));
            order.setCustomerId(customer.getId());
            order.setStyleId(style.getId());
            order.setFabricId(fabric.getId());
            order.setMeasurementId(measurement != null ? measurement.getId() : missingMeasurementId);
            order.setUsageMeters(new BigDecimal("3.00"));
            order.setOrderDate(LocalDate.now().minusDays(20));
            order.setDeliveryDate(LocalDate.now().plusDays(5));
            BigDecimal fabricCost = new BigDecimal("300.00");
            BigDecimal craftFee = new BigDecimal("1000.00");
            BigDecimal total = new BigDecimal("1300.00");
            order.setFabricCost(fabricCost);
            order.setCraftFee(craftFee);
            order.setTotalPrice(total);
            order.setDeposit(new BigDecimal("650.00"));
            order.setBalance(new BigDecimal("650.00"));
            order.setDepositPaid(Boolean.TRUE);
            order.setBalancePaid(Boolean.TRUE);
            order.setStatus(status);
            return orderRepository.save(order);
        }
    }
}
