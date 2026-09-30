package com.garment.factory.service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.garment.factory.common.BizException;
import com.garment.factory.common.ConflictException;
import com.garment.factory.domain.CustomOrder;
import com.garment.factory.domain.Measurement;
import com.garment.factory.domain.OrderMeasurementSnapshot;
import com.garment.factory.dto.OrderMeasurementSnapshotDto;
import com.garment.factory.repository.CustomOrderRepository;
import com.garment.factory.repository.MeasurementRepository;
import com.garment.factory.repository.OrderMeasurementSnapshotRepository;

/**
 * 订单量体快照：冻结 / 读取 / 旧数据补齐 / 人工核对的唯一入口。
 *
 * 口径铁律：订单看板、订单详情、工序侧需要尺寸时只认本服务给出的快照，
 * 快照一旦建立永不由量体记录的后续编辑改写（MANUAL_CONFIRMED 也不被自动补齐覆盖）。
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

    /**
     * 下单确认：把当次采用的量体记录完整冻结成快照。
     * 与订单保存在同一事务内执行——任一步失败整体回滚，不会出现「订单已建、快照只写一半」。
     */
    @Transactional
    public OrderMeasurementSnapshot freezeForNewOrder(CustomOrder order, Measurement measurement) {
        if (snapshotRepository.findByOrderId(order.getId()).isPresent()) {
            // 极端重入下也保证一单一份
            throw new ConflictException("该订单已存在量体快照，请勿重复冻结");
        }
        OrderMeasurementSnapshot snap = new OrderMeasurementSnapshot();
        snap.setOrderId(order.getId());
        snap.setMeasurementId(measurement.getId());
        copyFromMeasurement(snap, measurement);
        snap.setStatus(OrderMeasurementSnapshot.STATUS_FROZEN);
        snap.setSource("FROZEN");
        LocalDateTime now = LocalDateTime.now();
        snap.setFrozenAt(now);
        return snapshotRepository.save(snap);
    }

    /**
     * 读取订单冻结快照；旧订单还没快照时在本事务内同步补齐（惰性、幂等、只形成一份）。
     * 任何读路径（看板/详情/工序）都走这里，保证口径一致、刷新后不从快照退回实时记录。
     */
    @Transactional
    public OrderMeasurementSnapshotDto getOrBackfill(Long orderId) {
        OrderMeasurementSnapshot snap = snapshotRepository.findByOrderId(orderId).orElse(null);
        if (snap == null) {
            snap = backfillOne(orderId);
        }
        return toDto(snap);
    }

    /**
     * 批量补齐旧订单：可重复执行，已存在快照（含待核对/人工确认）的订单一律跳过。
     * 并发多人同时补齐时，靠订单行锁 + 快照唯一归属串行化，较晚的执行者收到可理解的提示。
     */
    @Transactional
    public Map<String, Object> backfillAll() {
        List<Long> missing = snapshotRepository.findOrderIdsWithoutSnapshot();
        int frozen = 0;
        int pending = 0;
        int skipped = 0;
        for (Long orderId : missing) {
            String result = backfillOneGuarded(orderId);
            switch (result) {
                case "FROZEN" -> frozen++;
                case "PENDING" -> pending++;
                default -> skipped++;
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("scanned", missing.size());
        summary.put("frozen", frozen);
        summary.put("pendingVerify", pending);
        summary.put("skipped", skipped);
        summary.put("remainingWithoutSnapshot", snapshotRepository.findOrderIdsWithoutSnapshot().size());
        return summary;
    }

    /**
     * 待核对快照的人工核对：由人工录入/确认完整尺寸后收口为 MANUAL_CONFIRMED。
     * 自动补齐永远跳过已有人工结果的快照，不会用后补数据改写人工确认。
     */
    @Transactional
    public OrderMeasurementSnapshotDto confirmPending(Long orderId, ManualMeasurementReq req) {
        OrderMeasurementSnapshot snap = snapshotRepository.findByOrderIdForUpdate(orderId)
                .orElseThrow(() -> new BizException("该订单尚无快照，无法核对"));
        if (!OrderMeasurementSnapshot.STATUS_PENDING_VERIFY.equals(snap.getStatus())) {
            throw new BizException("仅「待核对」快照需要人工确认，当前状态为 " + snap.getStatus());
        }
        if (req == null || req.getOperator() == null || req.getOperator().isBlank()) {
            throw new BizException("请填写核对操作人");
        }
        if (req.getTailorName() == null || req.getTailorName().isBlank() || req.getMeasureDate() == null) {
            throw new BizException("核对时至少需确认量体师与量体日期");
        }
        java.math.BigDecimal[] values = {req.getBust(), req.getWaist(), req.getHip(), req.getShoulder(),
                req.getSleeveLength(), req.getBodyLength(), req.getPantsLength()};
        String[] parts = {"bust", "waist", "hip", "shoulder", "sleeveLength", "bodyLength", "pantsLength"};
        for (int i = 0; i < parts.length; i++) {
            java.math.BigDecimal v = values[i];
            if (v == null) {
                throw new BizException(com.garment.factory.domain.GarmentDict.partName(parts[i]) + "不能为空，"
                        + "无法取得的尺寸请继续保持待核对，不能提交半成品快照");
            }
            if (!com.garment.factory.domain.GarmentDict.validPart(parts[i], v)) {
                throw new BizException(com.garment.factory.domain.GarmentDict.partName(parts[i]) + "应在 "
                        + com.garment.factory.domain.GarmentDict.partMin(parts[i]) + " ~ "
                        + com.garment.factory.domain.GarmentDict.partMax(parts[i]) + " cm 之间");
            }
        }
        snap.setTailorName(req.getTailorName());
        snap.setMeasureDate(req.getMeasureDate());
        snap.setBust(req.getBust());
        snap.setWaist(req.getWaist());
        snap.setHip(req.getHip());
        snap.setShoulder(req.getShoulder());
        snap.setSleeveLength(req.getSleeveLength());
        snap.setBodyLength(req.getBodyLength());
        snap.setPantsLength(req.getPantsLength());
        snap.setMeasureRemark(req.getMeasureRemark());
        if (req.getRecordNo() != null && !req.getRecordNo().isBlank()) {
            snap.setRecordNo(req.getRecordNo());
        }
        snap.setStatus(OrderMeasurementSnapshot.STATUS_MANUAL_CONFIRMED);
        snap.setVerifyNote("人工核对完成：" + (req.getNote() == null ? "" : req.getNote()));
        snap.setConfirmedBy(req.getOperator());
        snap.setConfirmedAt(LocalDateTime.now());
        return toDto(snap);
    }

    // ---------------- 内部 ----------------

    /**
     * 补齐单订单：锁订单行后复查快照（防止并发双写），再按来源记录是否存在决定冻结或挂待核对。
     * 返回 FROZEN / PENDING / SKIPPED。
     */
    private String backfillOneGuarded(Long orderId) {
        CustomOrder order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null) {
            return "SKIPPED";
        }
        Optional<OrderMeasurementSnapshot> existing = snapshotRepository.findByOrderId(orderId);
        if (existing.isPresent()) {
            return "SKIPPED";
        }
        OrderMeasurementSnapshot snap = doBackfill(order);
        return OrderMeasurementSnapshot.STATUS_PENDING_VERIFY.equals(snap.getStatus()) ? "PENDING" : "FROZEN";
    }

    /** 惰性补齐入口（读路径调用）：订单必须存在；并发下后来者复用已建快照 */
    private OrderMeasurementSnapshot backfillOne(Long orderId) {
        CustomOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BizException("订单不存在"));
        return snapshotRepository.findByOrderId(orderId).orElseGet(() -> doBackfill(order));
    }

    /**
     * 执行补齐：原量体记录还在 → 完整冻结；原记录已删除 / 订单根本没绑定记录 → 挂待核对，
     * 严禁用客户其它量体记录或任何猜测值伪造尺寸。
     */
    private OrderMeasurementSnapshot doBackfill(CustomOrder order) {
        LocalDateTime now = LocalDateTime.now();
        OrderMeasurementSnapshot snap = new OrderMeasurementSnapshot();
        snap.setOrderId(order.getId());
        snap.setMeasurementId(order.getMeasurementId());
        snap.setSource("BACKFILL");
        snap.setFrozenAt(now);
        snap.setBackfilledAt(now);
        Measurement source = order.getMeasurementId() == null ? null
                : measurementRepository.findById(order.getMeasurementId()).orElse(null);
        if (source != null) {
            copyFromMeasurement(snap, source);
            snap.setStatus(OrderMeasurementSnapshot.STATUS_FROZEN);
        } else {
            snap.setStatus(OrderMeasurementSnapshot.STATUS_PENDING_VERIFY);
            snap.setVerifyNote(order.getMeasurementId() == null
                    ? "历史订单未绑定量体记录，尺寸无法自动补齐，请人工核对"
                    : "原量体记录（ID " + order.getMeasurementId() + "）已删除，尺寸无法考证，严禁伪造，请人工核对");
        }
        return snapshotRepository.save(snap);
    }

    private void copyFromMeasurement(OrderMeasurementSnapshot snap, Measurement m) {
        snap.setRecordNo(m.getRecordNo());
        snap.setCustomerName(m.getCustomerName());
        snap.setTailorName(m.getTailorName());
        snap.setMeasureDate(m.getMeasureDate());
        snap.setBust(m.getBust());
        snap.setWaist(m.getWaist());
        snap.setHip(m.getHip());
        snap.setShoulder(m.getShoulder());
        snap.setSleeveLength(m.getSleeveLength());
        snap.setBodyLength(m.getBodyLength());
        snap.setPantsLength(m.getPantsLength());
        snap.setMeasureRemark(m.getRemark());
    }

    private OrderMeasurementSnapshotDto toDto(OrderMeasurementSnapshot snap) {
        if (snap == null) {
            return null;
        }
        return new OrderMeasurementSnapshotDto(snap.getMeasurementId(), snap.getRecordNo(), snap.getTailorName(),
                snap.getMeasureDate(), snap.getBust(), snap.getWaist(), snap.getHip(), snap.getShoulder(),
                snap.getSleeveLength(), snap.getBodyLength(), snap.getPantsLength(), snap.getMeasureRemark(),
                snap.getStatus(), snap.getVerifyNote(), snap.getSource(), snap.getFrozenAt(), snap.getConfirmedBy());
    }

    /** 人工核对入参 */
    public static class ManualMeasurementReq {
        private String recordNo;
        private String tailorName;
        private java.time.LocalDate measureDate;
        private java.math.BigDecimal bust;
        private java.math.BigDecimal waist;
        private java.math.BigDecimal hip;
        private java.math.BigDecimal shoulder;
        private java.math.BigDecimal sleeveLength;
        private java.math.BigDecimal bodyLength;
        private java.math.BigDecimal pantsLength;
        private String measureRemark;
        private String note;
        private String operator;

        public String getRecordNo() {
            return recordNo;
        }

        public void setRecordNo(String recordNo) {
            this.recordNo = recordNo;
        }

        public String getTailorName() {
            return tailorName;
        }

        public void setTailorName(String tailorName) {
            this.tailorName = tailorName;
        }

        public java.time.LocalDate getMeasureDate() {
            return measureDate;
        }

        public void setMeasureDate(java.time.LocalDate measureDate) {
            this.measureDate = measureDate;
        }

        public java.math.BigDecimal getBust() {
            return bust;
        }

        public void setBust(java.math.BigDecimal bust) {
            this.bust = bust;
        }

        public java.math.BigDecimal getWaist() {
            return waist;
        }

        public void setWaist(java.math.BigDecimal waist) {
            this.waist = waist;
        }

        public java.math.BigDecimal getHip() {
            return hip;
        }

        public void setHip(java.math.BigDecimal hip) {
            this.hip = hip;
        }

        public java.math.BigDecimal getShoulder() {
            return shoulder;
        }

        public void setShoulder(java.math.BigDecimal shoulder) {
            this.shoulder = shoulder;
        }

        public java.math.BigDecimal getSleeveLength() {
            return sleeveLength;
        }

        public void setSleeveLength(java.math.BigDecimal sleeveLength) {
            this.sleeveLength = sleeveLength;
        }

        public java.math.BigDecimal getBodyLength() {
            return bodyLength;
        }

        public void setBodyLength(java.math.BigDecimal bodyLength) {
            this.bodyLength = bodyLength;
        }

        public java.math.BigDecimal getPantsLength() {
            return pantsLength;
        }

        public void setPantsLength(java.math.BigDecimal pantsLength) {
            this.pantsLength = pantsLength;
        }

        public String getMeasureRemark() {
            return measureRemark;
        }

        public void setMeasureRemark(String measureRemark) {
            this.measureRemark = measureRemark;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }

        public String getOperator() {
            return operator;
        }

        public void setOperator(String operator) {
            this.operator = operator;
        }
    }
}
