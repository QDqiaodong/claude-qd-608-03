package com.garment.factory.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.garment.factory.common.BizException;
import com.garment.factory.domain.Customer;
import com.garment.factory.domain.GarmentDict;
import com.garment.factory.domain.Measurement;
import com.garment.factory.dto.MeasurementListItemDto;
import com.garment.factory.dto.MeasurementOrderRefDto;
import com.garment.factory.dto.MeasurementSaveReq;
import com.garment.factory.dto.SliceView;
import com.garment.factory.repository.CustomerRepository;
import com.garment.factory.repository.MeasurementRepository;

/**
 * 量体记录：部位尺寸的合理区间在录入时强校验。
 *
 * <p>与订单快照的协作口径：</p>
 * <ul>
 *   <li>编辑量体记录：允许维护新值，但列表/抽屉明确提示「使用中的订单不会随之变化」，
 *       历史订单始终展示自己的冻结快照；编辑与下单并发时由量体记录行锁串行。</li>
 *   <li>删除量体记录：被【未交付订单】引用时服务端拒绝并列出阻断来源（订单号+状态）；
 *       只被已交付订单引用的记录可以删除，已交付订单仍完整展示快照。</li>
 * </ul>
 */
@Service
public class MeasurementService {

    /** 已交付之后状态：只有这类订单引用的量体记录允许删除 */
    private static final String DELIVERED = "DELIVERED";

    private final MeasurementRepository measurementRepository;
    private final CustomerRepository customerRepository;

    public MeasurementService(MeasurementRepository measurementRepository, CustomerRepository customerRepository) {
        this.measurementRepository = measurementRepository;
        this.customerRepository = customerRepository;
    }

    @Transactional(readOnly = true)
    public SliceView<MeasurementListItemDto> list(Long customerId, String tailorName, int page, int size) {
        Slice<MeasurementListItemDto> slice = measurementRepository.sliceBy(customerId, trim(tailorName),
                PageRequest.of(page, size));
        fillOrderUsage(slice.getContent());
        return SliceView.of(slice);
    }

    @Transactional(readOnly = true)
    public List<MeasurementListItemDto> listByCustomer(Long customerId) {
        List<MeasurementListItemDto> rows =
                measurementRepository.sliceByCustomer(customerId, PageRequest.of(0, 50)).getContent();
        fillOrderUsage(rows);
        return rows;
    }

    /**
     * 回填每条量体记录被哪些订单引用：未交付订单明细用于页面提示与删除阻断说明，
     * 已交付订单只计数（这些订单只读自己的快照）。
     */
    private void fillOrderUsage(List<MeasurementListItemDto> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<Long> ids = rows.stream().map(MeasurementListItemDto::getId).toList();
        List<MeasurementOrderRefDto> refs = measurementRepository.findOrderRefsByMeasurementIds(ids);
        Map<Long, List<MeasurementOrderRefDto>> activeByMeasurement = new LinkedHashMap<>();
        Map<Long, Long> deliveredByMeasurement = new LinkedHashMap<>();
        for (MeasurementOrderRefDto ref : refs) {
            Long measurementId = ref.getMeasurementId();
            if (DELIVERED.equals(ref.getOrderStatus())) {
                deliveredByMeasurement.merge(measurementId, 1L, Long::sum);
            } else {
                activeByMeasurement.computeIfAbsent(measurementId, key -> new ArrayList<>()).add(ref);
            }
        }
        for (MeasurementListItemDto row : rows) {
            row.setActiveOrders(activeByMeasurement.getOrDefault(row.getId(), List.of()));
            row.setDeliveredOrderCount(deliveredByMeasurement.getOrDefault(row.getId(), 0L));
        }
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

    @Transactional
    public Measurement save(MeasurementSaveReq req) {
        validate(req);
        if (req.getId() == null && measurementRepository.existsByRecordNo(req.getRecordNo())) {
            throw new BizException("量体单号已存在：" + req.getRecordNo());
        }
        if (req.getId() != null && measurementRepository.existsByRecordNoAndIdNot(req.getRecordNo(), req.getId())) {
            throw new BizException("量体单号已被其它记录占用：" + req.getRecordNo());
        }
        // 校验通过后再对记录行加锁：与下单选用该记录的事务串行，避免锁了行却因校验失败回滚
        Measurement record = req.getId() == null ? new Measurement() : requireForUpdate(req.getId());
        String customerName = requireCustomer(req.getCustomerId()).getName();
        fill(record, req, customerName);
        return measurementRepository.save(record);
    }

    /**
     * 删除：被未交付订单引用时拒绝（列出全部阻断来源）；只被已交付订单引用时放行，
     * 已交付订单读取自身快照，不受记录删除影响。
     */
    @Transactional
    public void remove(Long id) {
        Measurement record = requireForUpdate(id);
        List<MeasurementOrderRefDto> refs = measurementRepository.findOrderRefsByMeasurementIds(List.of(id));
        List<MeasurementOrderRefDto> active = refs.stream()
                .filter(ref -> !DELIVERED.equals(ref.getOrderStatus()))
                .toList();
        if (!active.isEmpty()) {
            String detail = active.stream()
                    .map(ref -> "· " + ref.getOrderNo() + "（" + GarmentDict.orderStatusName(ref.getOrderStatus())
                            + "，客户 " + ref.getCustomerName() + "）")
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("");
            throw new BizException("量体记录「" + record.getRecordNo() + "」仍被 " + active.size()
                    + " 笔未交付订单使用，不能删除；这些订单的尺寸已按确认时冻结，"
                    + "如确需删除请先在订单侧处理（交付或撤销）。阻断来源：\n" + detail);
        }
        measurementRepository.delete(record);
    }

    /**
     * 校验并新建一条量体记录，供下单向导第 2 步复用。
     */
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

    /** 编辑/删除前持行锁：与下单选用该记录的事务串行，杜绝并发下的悬空引用与半份快照 */
    private Measurement requireForUpdate(Long id) {
        return measurementRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("量体记录不存在"));
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /** 各部位合理区间，给前端提示用 */
    public Map<String, Map<String, BigDecimal>> partRange() {
        return GarmentDict.partRanges();
    }
}
