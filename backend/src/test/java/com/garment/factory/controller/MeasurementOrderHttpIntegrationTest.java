package com.garment.factory.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.garment.factory.domain.Customer;
import com.garment.factory.domain.CustomOrder;
import com.garment.factory.domain.Fabric;
import com.garment.factory.domain.Measurement;
import com.garment.factory.domain.Style;
import com.garment.factory.dto.MeasurementSaveReq;
import com.garment.factory.dto.OrderCreateReq;
import com.garment.factory.repository.CustomerRepository;
import com.garment.factory.repository.FabricRepository;
import com.garment.factory.repository.StyleRepository;
import com.garment.factory.service.MeasurementService;
import com.garment.factory.service.OrderService;

/**
 * 量体记录删除拦截 / 下单竞争的 HTTP 语义验收：
 * 删除被未交付订单引用的记录必须由服务端返回 400 且消息含阻断订单来源；
 * 量体记录在下单期间被改动时下单接口返回 409，要求重新选择。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeasurementOrderHttpIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OrderService orderService;
    @Autowired
    private MeasurementService measurementService;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private StyleRepository styleRepository;
    @Autowired
    private FabricRepository fabricRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    private long seed() {
        long seq = System.nanoTime();
        Customer customer = new Customer();
        customer.setCustomerNo("KH-H" + seq);
        customer.setName("HTTP测试客户");
        customer.setPhone("138" + (seq % 100000000L));
        customer.setGender("女");
        customer.setVipLevel("NORMAL");
        customer = customerRepository.save(customer);

        Style style = new Style();
        style.setStyleNo("ST-H" + seq);
        style.setName("HTTP测试西装");
        style.setCategory("SUIT");
        style.setBasePrice(new BigDecimal("1000.00"));
        style.setCraftLevel(2);
        style.setOnSale(Boolean.TRUE);
        style = styleRepository.save(style);

        Fabric fabric = new Fabric();
        fabric.setFabricNo("FB-H" + seq);
        fabric.setName("HTTP测试面料");
        fabric.setComposition("WOOL");
        fabric.setColor("藏青");
        fabric.setColorHex("#000000");
        fabric.setStockMeters(new BigDecimal("999.00"));
        fabric.setUnitPrice(new BigDecimal("100.00"));
        fabric.setSupplier("测试供应商");
        fabric = fabricRepository.save(fabric);

        MeasurementSaveReq mr = new MeasurementSaveReq();
        mr.setRecordNo("ME-H" + seq);
        mr.setCustomerId(customer.getId());
        mr.setTailorName("方绮");
        mr.setMeasureDate(LocalDate.now().minusDays(1));
        mr.setBust(new BigDecimal("86.00"));
        mr.setWaist(new BigDecimal("68.00"));
        mr.setHip(new BigDecimal("92.00"));
        mr.setShoulder(new BigDecimal("38.00"));
        mr.setSleeveLength(new BigDecimal("57.00"));
        mr.setBodyLength(new BigDecimal("62.00"));
        mr.setPantsLength(new BigDecimal("96.00"));
        Measurement measurement = measurementService.createForOrder(mr, customer);

        OrderCreateReq req = new OrderCreateReq();
        req.setCustomerId(customer.getId());
        req.setStyleId(style.getId());
        req.setFabricId(fabric.getId());
        req.setMeasurementId(measurement.getId());
        req.setExpectedMeasurementVersion(measurement.getVersion());
        req.setUsageMeters(new BigDecimal("3.00"));
        req.setDeliveryDate(LocalDate.now().plusDays(10));
        req.setPayDeposit(Boolean.TRUE);
        CustomOrder order = orderService.create(req);
        org.junit.jupiter.api.Assertions.assertEquals("ORDERED", order.getStatus());
        return measurement.getId();
    }

    @Test
    void deletingMeasurementUsedByActiveOrder_isRejectedByServerWithSource() throws Exception {
        Long measurementId = seed();
        mockMvc.perform(delete("/api/measurements/" + measurementId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("未交付订单")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("ORD-2026")));
    }

    @Test
    void creatingOrderWithStaleMeasurementVersion_returnsConflict() throws Exception {
        long seq = System.nanoTime();
        Customer customer = new Customer();
        customer.setCustomerNo("KH-C" + seq);
        customer.setName("竞争测试客户");
        customer.setPhone("137" + (seq % 100000000L));
        customer.setGender("女");
        customer.setVipLevel("NORMAL");
        customer = customerRepository.save(customer);

        Style style = new Style();
        style.setStyleNo("ST-C" + seq);
        style.setName("竞争测试西装");
        style.setCategory("SUIT");
        style.setBasePrice(new BigDecimal("1000.00"));
        style.setCraftLevel(2);
        style.setOnSale(Boolean.TRUE);
        style = styleRepository.save(style);

        Fabric fabric = new Fabric();
        fabric.setFabricNo("FB-C" + seq);
        fabric.setName("竞争测试面料");
        fabric.setComposition("WOOL");
        fabric.setColor("藏青");
        fabric.setColorHex("#000000");
        fabric.setStockMeters(new BigDecimal("999.00"));
        fabric.setUnitPrice(new BigDecimal("100.00"));
        fabric.setSupplier("测试供应商");
        fabric = fabricRepository.save(fabric);

        MeasurementSaveReq mr = new MeasurementSaveReq();
        mr.setRecordNo("ME-C" + seq);
        mr.setCustomerId(customer.getId());
        mr.setTailorName("方绮");
        mr.setMeasureDate(LocalDate.now().minusDays(1));
        mr.setBust(new BigDecimal("86.00"));
        mr.setWaist(new BigDecimal("68.00"));
        mr.setHip(new BigDecimal("92.00"));
        mr.setShoulder(new BigDecimal("38.00"));
        mr.setSleeveLength(new BigDecimal("57.00"));
        mr.setBodyLength(new BigDecimal("62.00"));
        mr.setPantsLength(new BigDecimal("96.00"));
        Measurement measurement = measurementService.createForOrder(mr, customer);
        Long staleVersion = measurement.getVersion();

        mr.setId(measurement.getId());
        mr.setBust(new BigDecimal("90.00"));
        measurementService.save(mr);

        OrderCreateReq req = new OrderCreateReq();
        req.setCustomerId(customer.getId());
        req.setStyleId(style.getId());
        req.setFabricId(fabric.getId());
        req.setMeasurementId(measurement.getId());
        req.setExpectedMeasurementVersion(staleVersion);
        req.setUsageMeters(new BigDecimal("3.00"));
        req.setDeliveryDate(LocalDate.now().plusDays(10));
        req.setPayDeposit(Boolean.TRUE);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已被他人修改")));
    }
}
