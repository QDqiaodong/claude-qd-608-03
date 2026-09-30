package com.garment.factory.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.garment.factory.common.BizException;
import com.garment.factory.common.ConflictException;
import com.garment.factory.domain.CustomOrder;
import com.garment.factory.domain.GarmentDict;
import com.garment.factory.domain.Measurement;
import com.garment.factory.domain.OrderMeasurementSnapshot;
import com.garment.factory.dto.MeasurementConfirmReq;
import com.garment.factory.dto.OrderMeasurementView;
import com.garment.factory.repository.CustomOrderRepository;
import com.garment.factory.repository.MeasurementRepository;
import com.garment.factory.repository.OrderMeasurementSnapshotRepository;

/**
 * 订单量体快照：下单冻结、读取统一口径、旧数据幂等补齐、缺失记录人工核对。
 *
 * <p>并发约定：所有「确保某订单有快照」的写路径都先锁订单行（{@code findByIdForUpdate}），
 * 把同订单的并发补齐/人工确认/下单串行化；order_id 唯一索引是最后兜底，
 * 竞争失败者收到 409 友好提示，而不是产生两份快照。</p>
 */
@Service
public class OrderSnapshotService {

    private final OrderMeasurementSnapshotRepository snapshotRepository;
    private final CustomOrderRepository orderRepository;
    private final MeasurementRepository measurementRepository;

    public OrderSnapshotService(OrderMeasurementSnapshotRepository snapshotRepository,
                                CustomOrderRepository orderRepository,
                                MeasurementRepository measurementRepository) {
        this.snapshotRepository = snapshotRepository;
        this.orderRepository = orderRepository;
        this.measurementRepository = measurementRepository;
    }

    // ---------------- 下单冻结 ----------------

    /**
     * 下单确认时冻结当次采用的完整量体事实。调用方已持量体记录行锁。
     */
    @Transactional
    public OrderMeasurementSnapshot freezeForNewOrder(CustomOrder order, Measurement source, String customerName) {
        OrderMeasurementSnapshot snapshot = new OrderMeasurementSnapshot();
        snapshot.setOrderId(order.getId());
        snapshot.setMeasurementId(source.getId());
        snapshot.setRecordNo(source.getRecordNo());
        snapshot.setCustomerId(source.getCustomerId());
        snapshot.setCustomerName(customerName);
        snapshot.setTailorName(source.getTailorName());
        snapshot.setMeasureDate(source.getMeasureDate());
        snapshot.setBust(source.getBust());
        snapshot.setWaist(source.getWaist());
        snapshot.setHip(source.getHip());
        snapshot.setShoulder(source.getShoulder());
        snapshot.setSleeveLength(source.getSleeveLength());
        snapshot.setBodyLength(source.getBodyLength());
        snapshot.setPantsLength(source.getPantsLength());
        snapshot.setRemark(source.getRemark());
        snapshot.setStatus(OrderMeasurementSnapshot.STATUS_FROZEN);
        return persistNew(snapshot);
    }

    // ---------------- 读取统一口径 ----------------

    /**
     * 订单看板/列表等只读场景批量取量体口径：有快照读快照；旧订单无快照则实时读记录兼容展示。
     * 只读不写，补齐动作走显式接口或订单详情的惰性补齐。
     */
    @Transactional(readOnly = true)
    public Map<Long, OrderMeasurementView> viewsForOrders(List<Long> orderIds,
                                                          Map<Long, Long> measurementIdByOrder,
                                                          Map<Long, String> customerNameByOrder) {
        Map<Long, OrderMeasurementView> result = new LinkedHashMap<>();
        if (orderIds.isEmpty()) {
            return result;
        }
        Map<Long, OrderMeasurementSnapshot> snapshotByOrder = new LinkedHashMap<>();
        for (OrderMeasurementSnapshot snapshot : snapshotRepository.findByOrderIdIn(orderIds)) {
            snapshotByOrder.put(snapshot.getOrderId(), snapshot);
        }
        List<Long> liveIds = measurementIdByOrder.values().stream()
                .filter(id -> id != null).distinct().toList();
        Map<Long, Measurement> liveById = new LinkedHashMap<>();
        if (!liveIds.isEmpty()) {
            for (Measurement m : measurementRepository.findAllById(liveIds)) {
                liveById.put(m.getId(), m);
            }
        }
        for (Long orderId : orderIds) {
            OrderMeasurementSnapshot snapshot = snapshotByOrder.get(orderId);
            if (snapshot != null) {
                result.put(orderId, toView(snapshot));
                continue;
            }
            // 旧订单兼容：无快照时回退实时记录；原记录也丢了则标待核对，不伪造尺寸
            Long measurementId = measurementIdByOrder.get(orderId);
            Measurement live = measurementId == null ? null : liveById.get(measurementId);
            result.put(orderId, legacyView(orderId, measurementId,
                    customerNameByOrder.get(orderId), live));
        }
        return result;
    }

    /**
     * 订单详情：确保旧订单至少已有一份快照（惰性、可重复执行），再返回统一口径。
     * 读路径内的惰性补齐走可写事务；与显式批量补齐共用同一把订单行锁。
     */
    @Transactional
    public OrderMeasurementView viewForOrderDetail(CustomOrder order) {
        OrderMeasurementSnapshot snapshot = ensureSnapshot(order);
        return toView(snapshot);
    }

    @Transactional(readOnly = true)
    public Optional<OrderMeasurementSnapshot> findSnapshot(Long orderId) {
        return snapshotRepository.findByOrderId(orderId);
    }

    // ---------------- 旧数据补齐（幂等） ----------------

    /**
     * 确保订单存在且仅有一份快照：
     * 已有快照（含人工确认）直接返回，绝不用自动补齐覆盖；
     * 无快照时锁订单行后复查，再从原记录冻结，原记录缺失则落 MISSING 待核对行。
     */
    @Transactional
    public OrderMeasurementSnapshot ensureSnapshot(CustomOrder lockedHintOrder) {
        Long orderId = lockedHintOrder.getId();
        Optional<OrderMeasurementSnapshot> existing = snapshotRepository.findByOrderId(orderId);
        if (existing.isPresent()) {
            return existing.get();
        }
        // 锁订单行：并发补齐 / 人工确认在此串行，加锁顺序与整改台一致（订单优先）
        CustomOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BizException("订单不存在"));
        existing = snapshotRepository.findByOrderId(orderId);
        if (existing.isPresent()) {
            return existing.get();
        }
        OrderMeasurementSnapshot snapshot = backfillFromOrder(order);
        return persistNew(snapshot);
    }

    /**
     * 显式批量补齐全部缺快照的旧订单。重复执行只形成一份快照，
     * 已补齐/已人工确认的订单跳过并计数返回。
     */
    @Transactional
    public Map<String, Object> backfillAll() {
        List<Long> missingOrderIds = snapshotRepository.findOrderIdsWithoutSnapshot();
        int frozen = 0;
        int markedMissing = 0;
        List<String> missingOrderNos = new ArrayList<>();
        for (Long orderId : missingOrderIds) {
            CustomOrder order = orderRepository.findByIdForUpdate(orderId)
                    .orElseThrow(() -> new BizException("订单不存在"));
            // 锁后复查：另一事务可能刚补齐过（findOrderIdsWithoutSnapshot 与加锁之间的窗口）
            if (snapshotRepository.findByOrderId(orderId).isPresent()) {
                continue;
            }
            OrderMeasurementSnapshot snapshot = backfillFromOrder(order);
            persistNew(snapshot);
            if (OrderMeasurementSnapshot.STATUS_MISSING.equals(snapshot.getStatus())) {
                markedMissing++;
                missingOrderNos.add(order.getOrderNo());
            } else {
                frozen++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scanned", missingOrderIds.size());
        result.put("frozen", frozen);
        result.put("markedMissing", markedMissing);
        result.put("missingOrderNos", missingOrderNos);
        return result;
    }

    private OrderMeasurementSnapshot backfillFromOrder(CustomOrder order) {
        OrderMeasurementSnapshot snapshot = new OrderMeasurementSnapshot();
        snapshot.setOrderId(order.getId());
        snapshot.setCustomerId(order.getCustomerId());
        Measurement source = order.getMeasurementId() == null
                ? null
                : measurementRepository.findById(order.getMeasurementId()).orElse(null);
        if (source == null) {
            // 原量体记录已缺失：尺寸列保持 NULL，标待核对，等待人工补录，绝不伪造
            snapshot.setMeasurementId(order.getMeasurementId());
            snapshot.setStatus(OrderMeasurementSnapshot.STATUS_MISSING);
            snapshot.setRemark(order.getMeasurementId() == null
                    ? "历史订单未关联量体记录，尺寸待人工核对"
                    : "原量体记录（id=" + order.getMeasurementId() + "）已缺失，尺寸待人工核对");
            return snapshot;
        }
        snapshot.setMeasurementId(source.getId());
        snapshot.setRecordNo(source.getRecordNo());
        snapshot.setCustomerId(source.getCustomerId());
        snapshot.setCustomerName(source.getCustomerName());
        snapshot.setTailorName(source.getTailorName());
        snapshot.setMeasureDate(source.getMeasureDate());
        snapshot.setBust(source.getBust());
        snapshot.setWaist(source.getWaist());
        snapshot.setHip(source.getHip());
        snapshot.setShoulder(source.getShoulder());
        snapshot.setSleeveLength(source.getSleeveLength());
        snapshot.setBodyLength(source.getBodyLength());
        snapshot.setPantsLength(source.getPantsLength());
        snapshot.setRemark(source.getRemark());
        snapshot.setStatus(OrderMeasurementSnapshot.STATUS_FROZEN);
        return snapshot;
    }

    // ---------------- 缺失记录人工核对 ----------------

    /**
     * 原量体记录缺失（MISSING）的订单，人工核对后补录确认。
     * 自动补齐重复执行不会覆盖本行；已确认/已冻结的订单再确认得到明确冲突提示。
     */
    @Transactional
    public OrderMeasurementSnapshot confirmMissing(Long orderId, MeasurementConfirmReq req) {
        CustomOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BizException("订单不存在"));
        OrderMeasurementSnapshot snapshot = snapshotRepository.findByOrderId(orderId)
                .orElseGet(() -> persistNew(backfillFromOrder(order)));
        if (OrderMeasurementSnapshot.STATUS_MANUAL_CONFIRMED.equals(snapshot.getStatus())) {
            throw new ConflictException("订单 " + order.getOrderNo()
                    + " 的量体尺寸已经人工核对确认，不能重复补录；如需更正请走变更流程");
        }
        if (OrderMeasurementSnapshot.STATUS_FROZEN.equals(snapshot.getStatus())) {
            throw new ConflictException("订单 " + order.getOrderNo()
                    + " 已有完整冻结尺寸，无需人工补录；冻结尺寸不允许改写");
        }
        // 仅 MISSING（原记录缺失、待核对）可补录；锁行并确认状态后再校验入参，避免无谓回滚
        validate(req);
        // 仅 MISSING（原记录缺失、待核对）可补录
        snapshot.setRecordNo(blankToNull(req.getRecordNo()));
        snapshot.setTailorName(req.getTailorName().trim());
        snapshot.setMeasureDate(req.getMeasureDate());
        snapshot.setBust(req.getBust());
        snapshot.setWaist(req.getWaist());
        snapshot.setHip(req.getHip());
        snapshot.setShoulder(req.getShoulder());
        snapshot.setSleeveLength(req.getSleeveLength());
        snapshot.setBodyLength(req.getBodyLength());
        snapshot.setPantsLength(req.getPantsLength());
        snapshot.setRemark(req.getRemark());
        snapshot.setStatus(OrderMeasurementSnapshot.STATUS_MANUAL_CONFIRMED);
        snapshot.setConfirmedBy(req.getOperator().trim());
        snapshot.setFrozenAt(java.time.LocalDateTime.now());
        return snapshotRepository.save(snapshot);
    }

    private void validate(MeasurementConfirmReq req) {
        if (req.getOperator() == null || req.getOperator().isBlank()) {
            throw new BizException("请填写核对操作人");
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

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private OrderMeasurementSnapshot persistNew(OrderMeasurementSnapshot snapshot) {
        try {
            return snapshotRepository.saveAndFlush(snapshot);
        } catch (DataIntegrityViolationException e) {
            // order_id 唯一索引兜底：并发补齐竞争落败，提示以已存在快照为准
            throw new ConflictException("该订单的量体快照刚被其他操作建立，已为你保留同一份结果，请刷新查看");
        }
    }

    // ---------------- 视图装配 ----------------

    /** 撤销「已下单」订单时随订单删除其快照（订单不存在，冻结事实亦无意义） */
    @Transactional
    public void deleteForOrder(Long orderId) {
        snapshotRepository.findByOrderId(orderId).ifPresent(snapshotRepository::delete);
    }

    public OrderMeasurementView toView(OrderMeasurementSnapshot snapshot) {
        OrderMeasurementView view = new OrderMeasurementView();
        view.setMeasurementId(snapshot.getMeasurementId());
        view.setRecordNo(snapshot.getRecordNo());
        view.setCustomerId(snapshot.getCustomerId());
        view.setCustomerName(snapshot.getCustomerName());
        view.setTailorName(snapshot.getTailorName());
        view.setMeasureDate(snapshot.getMeasureDate());
        view.setBust(snapshot.getBust());
        view.setWaist(snapshot.getWaist());
        view.setHip(snapshot.getHip());
        view.setShoulder(snapshot.getShoulder());
        view.setSleeveLength(snapshot.getSleeveLength());
        view.setBodyLength(snapshot.getBodyLength());
        view.setPantsLength(snapshot.getPantsLength());
        view.setRemark(snapshot.getRemark());
        view.setStatus(snapshot.getStatus());
        view.setFrozenAt(snapshot.getFrozenAt());
        view.setConfirmedBy(snapshot.getConfirmedBy());
        if (OrderMeasurementSnapshot.STATUS_MISSING.equals(snapshot.getStatus())) {
            view.setFrozen(false);
            view.setPending(true);
            view.setStatusName("待核对");
        } else if (OrderMeasurementSnapshot.STATUS_MANUAL_CONFIRMED.equals(snapshot.getStatus())) {
            view.setFrozen(true);
            view.setPending(false);
            view.setStatusName("人工核对确认");
        } else {
            view.setFrozen(true);
            view.setPending(false);
            view.setStatusName("下单已冻结");
        }
        return view;
    }

    /** 无快照旧订单的只读兼容视图：有实时记录就回显（明确标注未冻结），没有就待核对。 */
    private OrderMeasurementView legacyView(Long orderId, Long measurementId, String customerName, Measurement live) {
        OrderMeasurementView view = new OrderMeasurementView();
        view.setMeasurementId(measurementId);
        if (live == null) {
            view.setCustomerName(customerName);
            view.setFrozen(false);
            view.setPending(true);
            view.setStatus("MISSING");
            view.setStatusName("待核对");
            view.setRemark(measurementId == null
                    ? "历史订单未关联量体记录，尺寸待人工核对"
                    : "原量体记录已缺失，尺寸待人工核对");
            return view;
        }
        view.setRecordNo(live.getRecordNo());
        view.setCustomerId(live.getCustomerId());
        view.setCustomerName(live.getCustomerName());
        view.setTailorName(live.getTailorName());
        view.setMeasureDate(live.getMeasureDate());
        view.setBust(live.getBust());
        view.setWaist(live.getWaist());
        view.setHip(live.getHip());
        view.setShoulder(live.getShoulder());
        view.setSleeveLength(live.getSleeveLength());
        view.setBodyLength(live.getBodyLength());
        view.setPantsLength(live.getPantsLength());
        view.setRemark(live.getRemark());
        view.setStatus("LEGACY_LIVE");
        view.setStatusName("历史兼容");
        view.setFrozen(false);
        view.setPending(false);
        return view;
    }
}
