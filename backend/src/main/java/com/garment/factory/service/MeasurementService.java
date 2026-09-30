package com.garment.factory.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.garment.factory.common.BizException;
import com.garment.factory.common.ConflictException;
import com.garment.factory.domain.Customer;
import com.garment.factory.domain.GarmentDict;
import com.garment.factory.domain.Measurement;
import com.garment.factory.dto.MeasurementListItemDto;
import com.garment.factory.dto.MeasurementSaveReq;
import com.garment.factory.dto.OrderRefDto;
import com.garment.factory.dto.SliceView;
import com.garment.factory.repository.CustomOrderRepository;
import com.garment.factory.repository.CustomerRepository;
import com.garment.factory.repository.MeasurementRepository;

/**
 * 量体记录：部位尺寸的合理区间在录入时强校验。
 * 记录与订单之间通过订单量体快照解耦：编辑记录不影响已确认订单，
 * 删除被【未交付订单】引用的记录由服务端拒绝并说明阻断订单；仅被已交付订单引用时可删除。
 */
@Service
public class MeasurementService {

    private final MeasurementRepository measurementRepository;
    private final CustomerRepository customerRepository;
    private final CustomOrderRepository orderRepository;

    public MeasurementService(MeasurementRepository measurementRepository, CustomerRepository customerRepository,
                              CustomOrderRepository orderRepository) {
        this.measurementRepository = measurementRepository;
        this.customerRepository = customerRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional(readOnly = true)
    public SliceView<MeasurementListItemDto> list(Long customerId, String tailorName, int page, int size) {
        Slice<MeasurementListItemDto> slice = measurementRepository.sliceBy(customerId, trim(tailorName),
                PageRequest.of(page, size));
        // 列表行直接带未交付/已交付引用计数（明细在编辑/删除时再取 usage）
        List<Long> ids = slice.getContent().stream().map(MeasurementListItemDto::getId).toList();
        if (!ids.isEmpty()) {
            Map<Long, long[]> usageMap = new java.util.HashMap<>();
            for (var u : orderRepository.summarizeUsageByMeasurementIds(ids)) {
                usageMap.put(u.getMeasurementId(), new long[]{u.getActiveCount(), u.getDeliveredCount()});
            }
            for (MeasurementListItemDto row : slice.getContent()) {
                long[] pair = usageMap.getOrDefault(row.getId(), new long[]{0, 0});
                row.setActiveOrderCount(pair[0]);
                row.setDeliveredOrderCount(pair[1]);
            }
        }
        return SliceView.of(slice);
    }

    @Transactional(readOnly = true)
    public List<MeasurementListItemDto> listByCustomer(Long customerId) {
        return measurementRepository.sliceByCustomer(customerId, PageRequest.of(0, 50)).getContent();
    }

    @Transactional(readOnly = true)
    public List<String> tailors() {
        return measurementRepository.sliceBy(null, "", PageRequest.of(0, 500)).getContent()
                .stream().map(MeasurementListItemDto::getTailorName).distinct().sorted().toList();
    }

    @Transactional(readOnly = true)
    public Measurement detail(Long id) {
        return measurementRepository.findById(id).orElseThrow(() -> new BizException("量体记录不存在"));
    }

    /**
     * 引用情况：编辑抽屉打开前 / 删除确认前调用。明确列出使用中的订单及状态，
     * 让操作人知道：编辑本记录不会改动这些订单当时确认的尺寸。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> usage(Long id) {
        Measurement record = measurementRepository.findById(id)
                .orElseThrow(() -> new BizException("量体记录不存在"));
        List<OrderRefDto> refs = orderRepository.findRefsByMeasurementId(id);
        List<Map<String, Object>> activeOrders = new java.util.ArrayList<>();
        List<Map<String, Object>> deliveredOrders = new java.util.ArrayList<>();
        for (OrderRefDto ref : refs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("orderId", ref.getOrderId());
            item.put("orderNo", ref.getOrderNo());
            item.put("status", ref.getStatus());
            item.put("statusName", GarmentDict.orderStatusName(ref.getStatus()));
            (ref.isActive() ? activeOrders : deliveredOrders).add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("measurementId", id);
        result.put("recordNo", record.getRecordNo());
        result.put("activeOrders", activeOrders);
        result.put("deliveredOrders", deliveredOrders);
        result.put("referenced", !refs.isEmpty());
        result.put("blockDelete", !activeOrders.isEmpty());
        return result;
    }

    @Transactional
    public Measurement save(MeasurementSaveReq req) {
        validate(req);
        Measurement record = req.getId() == null ? new Measurement() : require(req.getId());
        if (req.getId() == null && measurementRepository.existsByRecordNo(req.getRecordNo())) {
            throw new BizException("量体单号已存在：" + req.getRecordNo());
        }
        if (req.getId() != null && measurementRepository.existsByRecordNoAndIdNot(req.getRecordNo(), req.getId())) {
            throw new BizException("量体单号已被其它记录占用：" + req.getRecordNo());
        }
        String customerName = requireCustomer(req.getCustomerId()).getName();
        fill(record, req, customerName);
        try {
            // flush 立即触发乐观版本校验，把并发冲突转成可理解的 409，而不是在提交时才冒出 500
            return measurementRepository.saveAndFlush(record);
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockingFailureException e) {
            throw new ConflictException("量体记录「" + req.getRecordNo()
                    + "」刚被他人修改或删除，请刷新列表后基于最新版本再编辑");
        }
    }

    /**
     * 删除：
     *  - 被未交付订单引用 → 服务端拒绝（前端隐藏按钮绕不过），错误信息列出阻断订单号与状态；
     *  - 仅被已交付订单引用 → 允许删除，历史订单的尺寸事实保存在订单快照里，展示不受影响。
     * 与下单事务通过 measurement 行锁串行：下单进行中时删除会等待其提交后再重新判定引用。
     */
    @Transactional
    public void remove(Long id) {
        Measurement record = measurementRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("量体记录不存在或已被删除"));
        List<OrderRefDto> refs = orderRepository.findRefsByMeasurementId(id);
        List<OrderRefDto> active = refs.stream().filter(OrderRefDto::isActive).toList();
        if (!active.isEmpty()) {
            String detail = active.stream()
                    .map(r -> "· " + r.getOrderNo() + "（" + GarmentDict.orderStatusName(r.getStatus()) + "）")
                    .reduce((a, b) -> a + "\n" + b).orElse("");
            throw new ConflictException("量体记录「" + record.getRecordNo() + "」仍被 " + active.size()
                    + " 笔未交付订单使用，不能删除（订单已冻结确认时的尺寸快照，删除会丢失来源追溯）：\n"
                    + detail + "\n如尺寸已变化，请新建量体记录供后续订单使用。");
        }
        measurementRepository.delete(record);
    }

    /**
     * 校验并新建一条量体记录，供下单向导第 2 步复用。
     */
    @Transactional
    public Measurement createForOrder(MeasurementSaveReq req, Customer customer) {
        validate(req);
        if (measurementRepository.existsByRecordNo(req.getRecordNo())) {
            throw new BizException("量体单号已存在：" + req.getRecordNo());
        }
        Measurement record = new Measurement();
        fill(record, req, customer.getName());
        return measurementRepository.save(record);
    }

    private void fill(Measurement record, MeasurementSaveReq req, String customerName) {
        record.setRecordNo(req.getRecordNo());
        record.setCustomerId(req.getCustomerId());
        record.setCustomerName(customerName);
        record.setTailorName(req.getTailorName());
        record.setMeasureDate(req.getMeasureDate());
        record.setBust(req.getBust());
        record.setWaist(req.getWaist());
        record.setHip(req.getHip());
        record.setShoulder(req.getShoulder());
        record.setSleeveLength(req.getSleeveLength());
        record.setBodyLength(req.getBodyLength());
        record.setPantsLength(req.getPantsLength());
        record.setRemark(req.getRemark());
    }

    private void validate(MeasurementSaveReq req) {
        if (req.getCustomerId() == null) {
            throw new BizException("请先选择客户");
        }
        Customer customer = requireCustomer(req.getCustomerId());
        // 客户存在性校验后回填，避免下单向导里漏传
        req.setCustomerId(customer.getId());
        if (req.getRecordNo() == null || req.getRecordNo().isBlank()) {
            throw new BizException("量体单号不能为空");
        }
        if (req.getTailorName() == null || req.getTailorName().isBlank()) {
            throw new BizException("请填写量体师");
        }
        if (req.getMeasureDate() == null) {
            throw new BizException("请选择量体日期");
        }
        if (req.getMeasureDate().isAfter(LocalDate.now())) {
            throw new BizException("量体日期不能晚于今天");
        }
        checkPart("bust", req.getBust());
        checkPart("waist", req.getWaist());
        checkPart("hip", req.getHip());
        checkPart("shoulder", req.getShoulder());
        checkPart("sleeveLength", req.getSleeveLength());
        checkPart("bodyLength", req.getBodyLength());
        checkPart("pantsLength", req.getPantsLength());
        if (req.getHip().compareTo(req.getWaist()) < 0) {
            throw new BizException("臀围不应该小于腰围，请复核尺寸");
        }
        if (req.getBust().compareTo(req.getWaist()) < 0) {
            throw new BizException("胸围不应该小于腰围，请复核尺寸");
        }
    }

    private void checkPart(String part, BigDecimal value) {
        if (value == null) {
            throw new BizException(GarmentDict.partName(part) + "不能为空");
        }
        if (!GarmentDict.validPart(part, value)) {
            throw new BizException(GarmentDict.partName(part) + "应在 " + GarmentDict.partMin(part)
                    + " ~ " + GarmentDict.partMax(part) + " cm 之间");
        }
    }

    private Customer requireCustomer(Long id) {
        return customerRepository.findById(id).orElseThrow(() -> new BizException("客户不存在，请先在客户列表中确认"));
    }

    private Measurement require(Long id) {
        return measurementRepository.findById(id).orElseThrow(() -> new BizException("量体记录不存在"));
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /** 各部位合理区间，给前端提示用 */
    public Map<String, Map<String, BigDecimal>> partRange() {
        return GarmentDict.partRanges();
    }
}
