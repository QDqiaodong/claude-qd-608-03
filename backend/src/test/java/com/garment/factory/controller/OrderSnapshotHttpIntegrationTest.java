package com.garment.factory.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.garment.factory.domain.CustomOrder;
import com.garment.factory.domain.Customer;
import com.garment.factory.domain.Measurement;
import com.garment.factory.repository.CustomOrderRepository;
import com.garment.factory.repository.CustomerRepository;
import com.garment.factory.repository.FabricRepository;
import com.garment.factory.repository.MeasurementRepository;
import com.garment.factory.repository.OrderMeasurementSnapshotRepository;
import com.garment.factory.repository.StyleRepository;
import com.garment.factory.repository.WorkOrderRepository;

/**
 * 量体快照 HTTP 验收：删除拦截返回 409 且带阻断订单号；看板/详情返回冻结尺寸；
 * 旧订单补齐端点可重复执行（幂等）；待核对订单明确标识。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderSnapshotHttpIntegrationTest {

    @Autowired private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired private CustomerRepository customerRepository;
    @Autowired private StyleRepository styleRepository;
    @Autowired private FabricRepository fabricRepository;
    @Autowired private MeasurementRepository measurementRepository;
    @Autowired private CustomOrderRepository orderRepository;
    @Autowired private OrderMeasurementSnapshotRepository snapshotRepository;
    @Autowired private WorkOrderRepository workOrderRepository;
    @Autowired private com.garment.factory.service.OrderService orderService;

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

    @Test
    void deleteUsedMeasurementReturns409WithBlockerOrders() throws Exception {
        Customer c = newCustomer();
        Measurement m = newMeasurement(c, "88.00", "70.00");
        CustomOrder order = createOrder(c, m, m.getVersion());

        // 引用明细
        mockMvc.perform(get("/api/measurements/" + m.getId() + "/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.blockDelete").value(true))
                .andExpect(jsonPath("$.data.activeOrders[0].orderNo").value(order.getOrderNo()));

        // 删除：409，消息中含订单号
        String body = mockMvc.perform(delete("/api/measurements/" + m.getId()))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = mapper.readTree(body);
        assertTrue(json.path("message").asText().contains(order.getOrderNo()));

        // 看板返回冻结值
        mockMvc.perform(get("/api/orders/board"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.columns.ORDERED[0].measurementSnapshot.bust").value(88.00))
                .andExpect(jsonPath("$.data.columns.ORDERED[0].measurementSnapshot.status").value("FROZEN"));
    }

    @Test
    void editMeasurementDoesNotChangeOrderSnapshot() throws Exception {
        Customer c = newCustomer();
        Measurement m = newMeasurement(c, "88.00", "70.00");
        CustomOrder order = createOrder(c, m, m.getVersion());

        m.setBust(new BigDecimal("99.00"));
        measurementRepository.saveAndFlush(m);

        JsonNode board = mapper.readTree(mockMvc.perform(get("/api/orders/board"))
                .andReturn().getResponse().getContentAsString());
        JsonNode card = board.path("data").path("columns").path("ORDERED").get(0);
        assertEquals(88.00, card.path("measurementSnapshot").path("bust").asDouble(), 0.001);
        assertEquals(order.getOrderNo(), card.path("orderNo").asText());
    }

    @Test
    void backfillIsIdempotentAndFlagsMissingSourceAsPending() throws Exception {
        Customer c = newCustomer();
        // 旧订单 1：来源记录还在
        Measurement m = newMeasurement(c, "84.00", "66.00");
        legacyOrder(c, m.getId());
        // 旧订单 2：来源记录缺失
        legacyOrder(c, 888888L);

        mockMvc.perform(post("/api/orders/snapshots/backfill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.frozen").value(1))
                .andExpect(jsonPath("$.data.pendingVerify").value(1));

        // 再执行一次：全部跳过，只剩 0 个无快照订单
        mockMvc.perform(post("/api/orders/snapshots/backfill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.frozen").value(0))
                .andExpect(jsonPath("$.data.pendingVerify").value(0))
                .andExpect(jsonPath("$.data.remainingWithoutSnapshot").value(0));

        // 看板里缺失来源的订单带待核对标记，尺寸字段为空
        JsonNode board = mapper.readTree(mockMvc.perform(get("/api/orders/board"))
                .andReturn().getResponse().getContentAsString());
        boolean pendingSnap = board.toPrettyString().contains("PENDING_VERIFY");
        assertTrue(pendingSnap);
    }

    @Test
    void createWithStaleVersionReturns409() throws Exception {
        Customer c = newCustomer();
        Measurement m = newMeasurement(c, "88.00", "70.00");
        Integer seen = m.getVersion();
        m.setBust(new BigDecimal("90.00"));
        measurementRepository.saveAndFlush(m);

        long styleId = styleRepository.findAll().get(0).getId();
        long fabricId = fabricRepository.findAll().get(0).getId();
        String req = mapper.writeValueAsString(java.util.Map.of(
                "customerId", c.getId(),
                "styleId", styleId,
                "fabricId", fabricId,
                "measurementId", m.getId(),
                "measurementVersion", seen,
                "usageMeters", 2.0,
                "deliveryDate", LocalDate.now().plusDays(14).toString(),
                "payDeposit", true));
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isConflict());
        assertEquals(0, snapshotRepository.count());
    }

    // ---------------- fixtures ----------------

    private Customer newCustomer() {
        seq++;
        Customer c = new Customer();
        c.setCustomerNo("KH-H" + seq + "-" + Math.abs(System.nanoTime() % 100000));
        c.setName("HTTP客户" + seq);
        c.setPhone("1380000000" + (seq % 10));
        c.setGender("女");
        c.setVipLevel("NORMAL");
        return customerRepository.save(c);
    }

    private Measurement newMeasurement(Customer c, String bust, String waist) {
        seq++;
        var s = new com.garment.factory.domain.Style();
        s.setStyleNo("ST-H" + System.nanoTime());
        s.setName("HTTP款式");
        s.setCategory("SUIT");
        s.setBasePrice(new BigDecimal("1000.00"));
        s.setCraftLevel(2);
        s.setOnSale(true);
        styleRepository.save(s);

        var f = new com.garment.factory.domain.Fabric();
        f.setFabricNo("FB-H" + System.nanoTime());
        f.setName("HTTP面料");
        f.setComposition("WOOL");
        f.setColor("藏青");
        f.setColorHex("#001133");
        f.setStockMeters(new BigDecimal("100"));
        f.setUnitPrice(new BigDecimal("100.00"));
        f.setSupplier("HTTP供应商");
        fabricRepository.save(f);

        Measurement m = new Measurement();
        m.setRecordNo("ME-H" + seq + "-" + Math.abs(System.nanoTime() % 1000000));
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
        m.setRemark("HTTP 备注");
        return measurementRepository.save(m);
    }

    private CustomOrder createOrder(Customer c, Measurement m, Integer version) {
        var req = new com.garment.factory.dto.OrderCreateReq();
        req.setCustomerId(c.getId());
        req.setStyleId(styleRepository.findAll().get(0).getId());
        req.setFabricId(fabricRepository.findAll().get(0).getId());
        req.setMeasurementId(m.getId());
        req.setMeasurementVersion(version);
        req.setUsageMeters(new BigDecimal("2.0"));
        req.setDeliveryDate(LocalDate.now().plusDays(14));
        req.setPayDeposit(true);
        return orderService.create(req);
    }

    private CustomOrder legacyOrder(Customer c, Long measurementId) {
        seq++;
        CustomOrder o = new CustomOrder();
        o.setOrderNo("ORD-HLEG-" + seq + "-" + Math.abs(System.nanoTime() % 100000));
        o.setCustomerId(c.getId());
        o.setStyleId(styleRepository.findAll().get(0).getId());
        o.setFabricId(fabricRepository.findAll().get(0).getId());
        o.setMeasurementId(measurementId);
        o.setUsageMeters(new BigDecimal("2.0"));
        o.setOrderDate(LocalDate.now().minusDays(60));
        o.setDeliveryDate(LocalDate.now().minusDays(50));
        o.setFabricCost(new BigDecimal("200.00"));
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
