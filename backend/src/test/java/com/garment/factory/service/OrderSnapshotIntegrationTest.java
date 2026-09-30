package com.garment.factory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.garment.factory.common.ConflictException;
import com.garment.factory.domain.CustomOrder;
import com.garment.factory.domain.Customer;
import com.garment.factory.domain.Measurement;
import com.garment.factory.domain.OrderMeasurementSnapshot;
import com.garment.factory.dto.OrderCreateReq;
import com.garment.factory.dto.OrderMeasurementSnapshotDto;
import com.garment.factory.repository.CustomOrderRepository;
import com.garment.factory.repository.CustomerRepository;
import com.garment.factory.repository.MeasurementRepository;
import com.garment.factory.repository.OrderMeasurementSnapshotRepository;
import com.garment.factory.repository.StyleRepository;
import com.garment.factory.repository.FabricRepository;

/**
 * 订单量体快照验收：
 *  1) 新订单下单即冻结完整尺寸；之后编辑/删除量体记录不回写订单；
 *  2) 使用中记录删除被服务端拒绝并说明阻断订单；仅已交付引用时可删除且历史仍完整；
 *  3) 旧订单惰性/批量补齐幂等：记录还在→冻结，记录缺失→待核对不伪造，重复执行只形成一份；
 *  4) 人工核对结果不被后续补齐覆盖；
 *  5) 下单与编辑并发：版本不一致 409 要求重选；下单与删除并发：要么冻结成功要么明确失败，无半快照。
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderSnapshotIntegrationTest {

    @Autowired private OrderService orderService;
    @Autowired private MeasurementService measurementService;
    @Autowired private OrderSnapshotService snapshotService;
    @Autowired private com.garment.factory.repository.WorkOrderRepository workOrderRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private StyleRepository styleRepository;
    @Autowired private FabricRepository fabricRepository;
    @Autowired private MeasurementRepository measurementRepository;
    @Autowired private CustomOrderRepository orderRepository;
    @Autowired private OrderMeasurementSnapshotRepository snapshotRepository;

    private long seq;

    @BeforeEach
    void clean() {
        snapshotRepository.deleteAll();
        workOrderRepository.deleteAll();
        orderRepository.deleteAll();
        measurementRepository.deleteAll();
        fabricRepository.deleteAll();
        styleRepository.deleteAll();
        customerRepository.deleteAll();
    }

    // ---------------- fixtures ----------------

    private Customer customer() {
        seq++;
        Customer c = new Customer();
        c.setCustomerNo("KH-T" + seq + "-" + System.nanoTime() % 100000);
        c.setName("测试客户" + seq);
        c.setPhone("1390000000" + (seq % 10));
        c.setGender("女");
        c.setVipLevel("NORMAL");
        return customerRepository.save(c);
    }

    private long styleId() {
        return styleRepository.findAll().stream().findFirst().map(s -> {
            if (!s.getOnSale()) {
                s.setOnSale(true);
                styleRepository.save(s);
            }
            return s.getId();
        }).orElseGet(() -> {
            var s = new com.garment.factory.domain.Style();
            s.setStyleNo("ST-T" + System.nanoTime());
            s.setName("测试款式");
            s.setCategory("SUIT");
            s.setBasePrice(new BigDecimal("1000.00"));
            s.setCraftLevel(2);
            s.setOnSale(true);
            return styleRepository.save(s).getId();
        });
    }

    private long fabricId() {
        return fabricRepository.findAll().stream().findFirst().map(f -> {
            if (f.getStockMeters().compareTo(new BigDecimal("10")) < 0) {
                f.setStockMeters(new BigDecimal("100"));
                fabricRepository.save(f);
            }
            return f.getId();
        }).orElseGet(() -> {
            var f = new com.garment.factory.domain.Fabric();
            f.setFabricNo("FB-T" + System.nanoTime());
            f.setName("测试面料");
            f.setComposition("WOOL");
            f.setColor("藏青");
            f.setColorHex("#000033");
            f.setStockMeters(new BigDecimal("100"));
            f.setUnitPrice(new BigDecimal("100.00"));
            f.setSupplier("测试供应商");
            return fabricRepository.save(f).getId();
        });
    }
        return fabricRepository.findAll().stream().findFirst().map(f -> {
            if (f.getStockMeters().compareTo(new BigDecimal("10")) < 0) {
                f.setStockMeters(new BigDecimal("100"));
                fabricRepository.save(f);
            }
            return f.getId();
        }).orElseGet(() -> {
            var f = new com.garment.factory.domain.Fabric();
            f.setFabricNo("FB-T" + System.nanoTime());
            f.setName("测试面料");
            f.setComposition("WOOL");
            f.setColor("藏青");
            f.setColorHex("#000033");
            f.setStockMeters(new BigDecimal("100"));
            f.setUnitPrice(new BigDecimal("100.00"));
            f.setSupplier("测试供应商");
            return fabricRepository.save(f).getId();
        });
    }

    private Measurement measurement(Customer c, String bust, String waist) {
        seq++;
        Measurement m = new Measurement();
        m.setRecordNo("ME-T" + seq + "-" + Math.abs(System.nanoTime() % 1000000));
        m.setCustomerId(c.getId());
        m.setCustomerName(c.getName());
        m.setTailorName("方绮");
        m.setMeasureDate(LocalDate.now().minusDays(1));
        m.setBust(new BigDecimal(bust));
        m.setWaist(new BigDecimal(waist));
        m.setHip(new BigDecimal("95.00"));
        m.setShoulder(new BigDecimal("38.00"));
        m.setSleeveLength(new BigDecimal("57.00"));
        m.setBodyLength(new BigDecimal("62.00"));
        m.setPantsLength(new BigDecimal("96.00"));
        m.setRemark("测试备注");
        return measurementRepository.save(m);
    }

    private OrderCreateReq orderReq(Customer c, Measurement m, Integer version) {
        OrderCreateReq req = new OrderCreateReq();
        req.setCustomerId(c.getId());
        req.setStyleId(styleId());
        req.setFabricId(fabricId());
        req.setMeasurementId(m.getId());
        req.setMeasurementVersion(version);
        req.setUsageMeters(new BigDecimal("2.0"));
        req.setDeliveryDate(LocalDate.now().plusDays(14));
        req.setPayDeposit(true);
        return req;
    }

    // ---------------- 1. 冻结 + 编辑不回写 ----------------

    @Test
    void newOrderFreezesFullSnapshotAndLaterEditDoesNotRewrite() {
        Customer c = customer();
        Measurement m = measurement(c, "88.00", "70.00");
        CustomOrder order = orderService.create(orderReq(c, m, m.getVersion()));

        OrderMeasurementSnapshot snap = snapshotRepository.findByOrderId(order.getId()).orElseThrow();
        assertEquals(OrderMeasurementSnapshot.STATUS_FROZEN, snap.getStatus());
        assertEquals("88.00", snap.getBust().toPlainString());
        assertEquals("70.00", snap.getWaist().toPlainString());
        assertEquals("方绮", snap.getTailorName());
        assertEquals(m.getMeasureDate(), snap.getMeasureDate());
        assertEquals("测试备注", snap.getMeasureRemark());
        assertEquals(m.getId(), snap.getMeasurementId());

        // 下单后修改客户尺寸
        m.setBust(new BigDecimal("99.00"));
        m.setWaist(new BigDecimal("81.00"));
        m.setRemark("已改成新尺寸");
        measurementRepository.saveAndFlush(m);

        // 看板/详情读到的仍是冻结值
        OrderMeasurementSnapshotDto view = snapshotService.getOrBackfill(order.getId());
        assertEquals("88.00", view.getBust().toPlainString());
        assertEquals("70.00", view.getWaist().toPlainString());
        assertEquals("测试备注", view.getMeasureRemark());
        assertEquals("FROZEN", view.getStatus());

        Map<String, Object> detail = orderService.detail(order.getId());
        @SuppressWarnings("unchecked")
        Map<String, Object> snapInDetail = (Map<String, Object>) detail.get("measurementSnapshot");
        assertEquals("88.00", String.valueOf(snapInDetail.get("bust")));
    }

    // ---------------- 2. 删除拦截 / 已交付可删 ----------------

    @Test
    void deletingMeasurementUsedByActiveOrderIsRejectedWithBlockers() {
        Customer c = customer();
        Measurement m = measurement(c, "88.00", "70.00");
        CustomOrder order = orderService.create(orderReq(c, m, m.getVersion()));
        assertTrue("ORDERED".equals(order.getStatus()));

        ConflictException ex = assertThrows(ConflictException.class, () -> measurementService.remove(m.getId()));
        assertTrue(ex.getMessage().contains(order.getOrderNo()), "阻断信息应包含订单号：" + ex.getMessage());
        assertTrue(measurementRepository.findById(m.getId()).isPresent());

        Map<String, Object> usage = measurementService.usage(m.getId());
        assertEquals(Boolean.TRUE, usage.get("blockDelete"));
        assertEquals(1, ((java.util.List<?>) usage.get("activeOrders")).size());
    }

    @Test
    void deletingMeasurementReferencedOnlyByDeliveredOrderKeepsHistory() {
        Customer c = customer();
        Measurement m = measurement(c, "88.00", "70.00");
        CustomOrder order = orderService.create(orderReq(c, m, m.getVersion()));

        // 把订单推到已交付（结清尾款 + 逐级推进）
        orderRepository.updateStatus(order.getId(), "DELIVERED");
        order.setStatus("DELIVERED");

        // 仅被已交付订单引用 → 允许删除
        measurementService.remove(m.getId());
        assertTrue(measurementRepository.findById(m.getId()).isEmpty());

        // 历史订单的快照原尺寸完整保留
        OrderMeasurementSnapshot snap = snapshotRepository.findByOrderId(order.getId()).orElseThrow();
        assertEquals("88.00", snap.getBust().toPlainString());
        assertNotNull(snap.getRecordNo());
        assertEquals(OrderMeasurementSnapshot.STATUS_FROZEN, snap.getStatus());
    }

    // ---------------- 3. 旧订单补齐：有记录 / 缺记录 / 幂等 ----------------

    @Test
    void backfillFreezesWhenSourceExists_andIsIdempotent() {
        Customer c = customer();
        Measurement m = measurement(c, "86.50", "69.50");
        CustomOrder legacy = legacyOrder(c, m.getId());

        // 读路径惰性补齐
        OrderMeasurementSnapshotDto dto = snapshotService.getOrBackfill(legacy.getId());
        assertEquals("FROZEN", dto.getStatus());
        assertEquals("86.50", dto.getBust().toPlainString());

        // 批量补齐重复执行：不再产生第二份、不改变已有快照
        Map<String, Object> r1 = snapshotService.backfillAll();
        Map<String, Object> r2 = snapshotService.backfillAll();
        assertEquals(0, r2.get("frozen"));
        assertEquals(0, r2.get("pendingVerify"));
        long count = snapshotRepository.findAll().stream().filter(s -> s.getOrderId().equals(legacy.getId())).count();
        assertEquals(1, count);
        assertEquals(dto.getBust(), snapshotService.getOrBackfill(legacy.getId()).getBust());
    }

    @Test
    void backfillMarksPendingVerifyWhenSourceMissing_andNeverFakes() {
        Customer c = customer();
        CustomOrder legacy = legacyOrder(c, 999999L); // 原量体记录不存在

        OrderMeasurementSnapshotDto dto = snapshotService.getOrBackfill(legacy.getId());
        assertTrue(dto.isPendingVerify());
        assertNull(dto.getBust());
        assertNull(dto.getTailorName());
        assertNotNull(dto.getVerifyNote());

        // 再跑批量补齐也不会覆盖待核对状态
        snapshotService.backfillAll();
        OrderMeasurementSnapshot again = snapshotRepository.findByOrderId(legacy.getId()).orElseThrow();
        assertEquals(OrderMeasurementSnapshot.STATUS_PENDING_VERIFY, again.getStatus());
        assertNull(again.getBust());
    }

    @Test
    void manualConfirmIsNotOverwrittenByLaterBackfill() {
        Customer c = customer();
        CustomOrder legacy = legacyOrder(c, 999998L);
        snapshotService.getOrBackfill(legacy.getId()); // 先挂待核对

        var req = new OrderSnapshotService.ManualMeasurementReq();
        req.setOperator("主管");
        req.setTailorName("罗予");
        req.setMeasureDate(LocalDate.now().minusDays(30));
        req.setBust(new BigDecimal("91.00"));
        req.setWaist(new BigDecimal("73.00"));
        req.setHip(new BigDecimal("98.00"));
        req.setShoulder(new BigDecimal("39.00"));
        req.setSleeveLength(new BigDecimal("58.00"));
        req.setBodyLength(new BigDecimal("64.00"));
        req.setPantsLength(new BigDecimal("99.00"));
        req.setNote("找到纸质量体单");
        OrderMeasurementSnapshotDto confirmed = snapshotService.confirmPending(legacy.getId(), req);
        assertEquals("MANUAL_CONFIRMED", confirmed.getStatus());

        // 任何补齐都不能改写人工结果
        snapshotService.backfillAll();
        OrderMeasurementSnapshot stored = snapshotRepository.findByOrderId(legacy.getId()).orElseThrow();
        assertEquals(OrderMeasurementSnapshot.STATUS_MANUAL_CONFIRMED, stored.getStatus());
        assertEquals("91.00", stored.getBust().toPlainString());
        assertEquals("主管", stored.getConfirmedBy());
    }

    // ---------------- 4. 并发竞争 ----------------

    @Test
    void createWithStaleMeasurementVersionIsRejected() {
        Customer c = customer();
        Measurement m = measurement(c, "88.00", "70.00");
        Integer seen = m.getVersion();

        // 期间别人改了尺寸
        m.setBust(new BigDecimal("90.00"));
        measurementRepository.saveAndFlush(m);
        assertNotEquals(seen, m.getVersion());

        ConflictException ex = assertThrows(ConflictException.class,
                () -> orderService.create(orderReq(c, m, seen)));
        assertTrue(ex.getMessage().contains("重新选择"));
        // 失败后不得留下订单或半份快照
        assertTrue(snapshotRepository.findAll().isEmpty());
    }

    @Test
    void concurrentCreateAndDeleteLeavesEitherOrderOrError_neverHalfSnapshot() throws Exception {
        Customer c = customer();
        Measurement m = measurement(c, "88.00", "70.00");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> createErr = new AtomicReference<>();
        AtomicReference<Throwable> deleteErr = new AtomicReference<>();

        pool.submit(() -> {
            try {
                start.await();
                orderService.create(orderReq(c, m, null));
            } catch (Throwable t) {
                createErr.set(t);
            }
        });
        pool.submit(() -> {
            try {
                start.await();
                Thread.sleep(5);
                measurementService.remove(m.getId());
            } catch (Throwable t) {
                deleteErr.set(t);
            }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        long orderCount = orderRepository.count();
        long snapCount = snapshotRepository.count();
        // 铁律：订单数必须与快照数一致，不存在有订单无快照（写一半）的情况
        assertEquals(orderCount, snapCount);
        if (orderCount > 0) {
            // 订单建成 → 删除必被拒，快照完整
            assertNotNull(deleteErr.get());
            OrderMeasurementSnapshot snap = snapshotRepository.findAll().get(0);
            assertEquals("88.00", snap.getBust().toPlainString());
            assertTrue(measurementRepository.findById(m.getId()).isPresent());
        } else {
            // 删除先得手 → 下单必然失败且提示明确
            assertNotNull(createErr.get());
            assertTrue(createErr.get().getMessage().contains("重新选择")
                    || createErr.get().getMessage().contains("不存在"));
        }
    }

    // ---------------- helpers ----------------

    private CustomOrder legacyOrder(Customer c, Long measurementId) {
        seq++;
        CustomOrder o = new CustomOrder();
        o.setOrderNo("ORD-LEGACY-" + seq + "-" + Math.abs(System.nanoTime() % 100000));
        o.setCustomerId(c.getId());
        o.setStyleId(styleId());
        o.setFabricId(fabricId());
        o.setMeasurementId(measurementId);
        o.setUsageMeters(new BigDecimal("2.0"));
        o.setOrderDate(LocalDate.now().minusDays(60));
        o.setDeliveryDate(LocalDate.now().minusDays(50));
        BigDecimal cost = new BigDecimal("200.00");
        o.setFabricCost(cost);
        o.setCraftFee(new BigDecimal("1000.00"));
        o.setTotalPrice(new BigDecimal("1200.00"));
        o.setDeposit(new BigDecimal("600.00"));
        o.setBalance(new BigDecimal("600.00"));
        o.setDepositPaid(true);
        o.setBalancePaid(true);
        o.setStatus("DELIVERED");
        return orderRepository.save(o);
    }
}
