package com.garment.factory.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import com.garment.factory.common.ConflictException;
import com.garment.factory.domain.Customer;
import com.garment.factory.domain.CustomOrder;
import com.garment.factory.domain.Fabric;
import com.garment.factory.domain.GarmentDict;
import com.garment.factory.domain.Measurement;
import com.garment.factory.domain.Style;
import com.garment.factory.dto.CustomerOptionDto;
import com.garment.factory.dto.MeasurementSaveReq;
import com.garment.factory.dto.OrderAdvanceReq;
import com.garment.factory.dto.OrderBoardCardDto;
import com.garment.factory.dto.OrderCreateReq;
import com.garment.factory.dto.OrderListItemDto;
import com.garment.factory.dto.OrderMeasurementSnapshotDto;
import com.garment.factory.dto.PricePreviewDto;
import com.garment.factory.dto.PricePreviewReq;
import com.garment.factory.dto.SliceView;
import com.garment.factory.domain.FittingIssue;
import com.garment.factory.repository.CustomerRepository;
import com.garment.factory.repository.CustomOrderRepository;
import com.garment.factory.repository.FabricRepository;
import com.garment.factory.repository.MeasurementRepository;
import com.garment.factory.repository.OrderMeasurementSnapshotRepository;
import com.garment.factory.repository.StyleRepository;
import com.garment.factory.repository.WorkOrderRepository;

/**
 * 定制订单：向导式下单 + 状态流转 + 定金尾款。
 * 尺寸口径：下单时冻结量体快照（OrderSnapshotService），看板/详情/工序只读快照。
 */
@Service
public class OrderService {

    private final CustomOrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final StyleRepository styleRepository;
    private final FabricRepository fabricRepository;
    private final MeasurementRepository measurementRepository;
    private final WorkOrderRepository workOrderRepository;
    private final MeasurementService measurementService;
    private final WorkOrderService workOrderService;
    private final FittingIssueService fittingIssueService;
    private final OrderSnapshotService snapshotService;
    private final OrderMeasurementSnapshotRepository orderSnapshotRepository;

    public OrderService(CustomOrderRepository orderRepository, CustomerRepository customerRepository,
                        StyleRepository styleRepository, FabricRepository fabricRepository,
                        MeasurementRepository measurementRepository, WorkOrderRepository workOrderRepository,
                        MeasurementService measurementService, WorkOrderService workOrderService,
                        FittingIssueService fittingIssueService, OrderSnapshotService snapshotService,
                        OrderMeasurementSnapshotRepository orderSnapshotRepository) {
        this.orderRepository = orderRepository;
        this.customerRepository = customerRepository;
        this.styleRepository = styleRepository;
        this.fabricRepository = fabricRepository;
        this.measurementRepository = measurementRepository;
        this.workOrderRepository = workOrderRepository;
        this.measurementService = measurementService;
        this.workOrderService = workOrderService;
        this.fittingIssueService = fittingIssueService;
        this.snapshotService = snapshotService;
        this.orderSnapshotRepository = orderSnapshotRepository;
    }

    // ---------------- 列表 / 看板 ----------------

    @Transactional
    public SliceView<OrderListItemDto> list(String status, Long customerId, String keyword, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size);
        Slice<OrderListItemDto> slice = orderRepository.sliceBy(trim(status), customerId, trim(keyword), pageable);
        // 旧订单惰性补齐快照：补齐后重查，所有读路径都只展示冻结结果，不会回退实时量体记录
        if (slice.getContent().stream().anyMatch(row -> row.getMeasurementSnapshot() == null)) {
            slice.getContent().stream()
                    .filter(row -> row.getMeasurementSnapshot() == null)
                    .map(OrderListItemDto::getId)
                    .distinct()
                    .forEach(snapshotService::getOrBackfill);
            slice = orderRepository.sliceBy(trim(status), customerId, trim(keyword), pageable);
        }
        return SliceView.of(slice);
    }

    @Transactional
    public Map<String, Object> board() {
        List<OrderBoardCardDto> cards = orderRepository.findBoardCards();
        // 看板与详情/工序同一口径：缺快照的旧订单当场补齐（待核对也在此落定），补齐后重查杜绝版本不一致
        if (cards.stream().anyMatch(card -> card.getMeasurementSnapshot() == null)) {
            cards.stream()
                    .filter(card -> card.getMeasurementSnapshot() == null)
                    .map(OrderBoardCardDto::getId)
                    .distinct()
                    .forEach(snapshotService::getOrBackfill);
            cards = orderRepository.findBoardCards();
        }
        Map<Long, long[]> progress = new LinkedHashMap<>();
        for (OrderBoardCardDto card : cards) {
            long total = workOrderRepository.countByOrderId(card.getId());
            long done = workOrderRepository.countByOrderIdAndStatus(card.getId(), "DONE");
            progress.put(card.getId(), new long[]{total, done});
        }
        Map<String, List<OrderBoardCardDto>> columns = new LinkedHashMap<>();
        for (String status : GarmentDict.orderStatusOrder()) {
            columns.put(status, new ArrayList<>());
        }
        for (OrderBoardCardDto card : cards) {
            long[] pair = progress.getOrDefault(card.getId(), new long[]{0L, 0L});
            card.setWorkOrderTotal(pair[0]);
            card.setWorkOrderDone(pair[1]);
            columns.computeIfAbsent(card.getStatus(), key -> new ArrayList<>()).add(card);
        }
        // 整改台口径：未关闭问题数 / 阻断数 / 返修工单数 / 当前阶段，看板与整改台共用同一汇总
        Map<Long, Map<String, Object>> fitting = fittingIssueService.summariesByOrder(
                cards.stream().map(OrderBoardCardDto::getId).toList());
        cards.forEach(card -> card.setFitting(fitting.get(card.getId())));
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("total", cards.size());
        meta.put("columns", GarmentDict.orderStatuses());
        result.put("columns", columns);
        result.put("meta", meta);
        return result;
    }

    @Transactional
    public Map<String, Object> detail(Long id) {
        CustomOrder order = orderRepository.findById(id).orElseThrow(() -> new BizException("订单不存在"));
        OrderMeasurementSnapshotDto snapshot = snapshotService.getOrBackfill(id);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", order.getId());
        view.put("orderNo", order.getOrderNo());
        view.put("customerId", order.getCustomerId());
        view.put("styleId", order.getStyleId());
        view.put("fabricId", order.getFabricId());
        view.put("measurementId", order.getMeasurementId());
        view.put("usageMeters", order.getUsageMeters());
        view.put("orderDate", order.getOrderDate());
        view.put("deliveryDate", order.getDeliveryDate());
        view.put("fabricCost", order.getFabricCost());
        view.put("craftFee", order.getCraftFee());
        view.put("totalPrice", order.getTotalPrice());
        view.put("deposit", order.getDeposit());
        view.put("balance", order.getBalance());
        view.put("depositPaid", order.getDepositPaid());
        view.put("balancePaid", order.getBalancePaid());
        view.put("status", order.getStatus());
        view.put("remark", order.getRemark());
        view.put("measurementSnapshot", snapshot);
        return view;
    }

    @Transactional(readOnly = true)
    public List<CustomerOptionDto> customers() {
        return customerRepository.findAllByOrderByCustomerNoAsc().stream()
                .map(c -> new CustomerOptionDto(c.getId(), c.getCustomerNo(), c.getName(), c.getPhone(),
                        c.getVipLevel(), orderRepository.countByCustomerId(c.getId()),
                        measurementRepository.countByCustomerId(c.getId())))
                .toList();
    }

    // ---------------- 算价 / 下单 ----------------

    @Transactional(readOnly = true)
    public PricePreviewDto preview(PricePreviewReq req) {
        Style style = requireStyle(req.getStyleId());
        Fabric fabric = requireFabric(req.getFabricId());
        BigDecimal usage = req.getUsageMeters() == null ? BigDecimal.ZERO : req.getUsageMeters();
        if (usage.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("面料用量必须大于 0");
        }
        if (usage.compareTo(GarmentDict.MAX_USAGE) > 0) {
            throw new BizException("单笔订单用料不能超过 " + GarmentDict.MAX_USAGE + " 米，请拆单");
        }
        if (Boolean.FALSE.equals(style.getOnSale())) {
            throw new BizException("款式「" + style.getName() + "」已停售，不能下单");
        }
        if (fabric.getStockMeters().compareTo(usage) < 0) {
            throw new BizException("面料「" + fabric.getName() + "」当前库存 " + fabric.getStockMeters()
                    + " 米，不足本次用量 " + usage + " 米");
        }
        LocalDate today = LocalDate.now();
        LocalDate delivery = req.getDeliveryDate() == null ? today.plusDays(GarmentDict.MIN_LEAD_DAYS)
                : req.getDeliveryDate();
        if (delivery.isBefore(today.plusDays(GarmentDict.MIN_LEAD_DAYS))) {
            throw new BizException("定制订单的最短工期为 " + GarmentDict.MIN_LEAD_DAYS + " 天，交期应晚于 "
                    + today.plusDays(GarmentDict.MIN_LEAD_DAYS));
        }
        BigDecimal fabricCost = fabric.getUnitPrice().multiply(usage).setScale(2, RoundingMode.HALF_UP);
        BigDecimal basePrice = style.getBasePrice().setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalPrice = fabricCost.add(basePrice).setScale(2, RoundingMode.HALF_UP);
        BigDecimal deposit = totalPrice.multiply(GarmentDict.DEPOSIT_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal balance = totalPrice.subtract(deposit).setScale(2, RoundingMode.HALF_UP);
        return new PricePreviewDto(usage, fabric.getUnitPrice(), fabricCost, basePrice, style.getCraftLevel(),
                totalPrice, deposit, balance, delivery);
    }

    @Transactional
    public CustomOrder create(OrderCreateReq req) {
        Customer customer = customerRepository.findById(req.getCustomerId())
                .orElseThrow(() -> new BizException("请先选择客户"));
        Style style = requireStyle(req.getStyleId());
        Fabric fabric = requireFabric(req.getFabricId());
        if (Boolean.FALSE.equals(style.getOnSale())) {
            throw new BizException("款式「" + style.getName() + "」已停售，不能下单");
        }
        BigDecimal usage = req.getUsageMeters() == null ? BigDecimal.ZERO : req.getUsageMeters();
        if (usage.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("面料用量必须大于 0");
        }
        if (usage.compareTo(GarmentDict.MAX_USAGE) > 0) {
            throw new BizException("单笔订单用料不能超过 " + GarmentDict.MAX_USAGE + " 米，请拆单");
        }
        if (fabric.getStockMeters().compareTo(usage) < 0) {
            throw new BizException("面料「" + fabric.getName() + "」库存仅剩 " + fabric.getStockMeters()
                    + " 米，不足本次用量 " + usage + " 米");
        }
        LocalDate today = LocalDate.now();
        LocalDate delivery = req.getDeliveryDate();
        if (delivery == null || delivery.isBefore(today.plusDays(GarmentDict.MIN_LEAD_DAYS))) {
            throw new BizException("定制订单的最短工期为 " + GarmentDict.MIN_LEAD_DAYS + " 天，交期应晚于 "
                    + today.plusDays(GarmentDict.MIN_LEAD_DAYS));
        }

        // 第 2 步：可用已有量体记录，也可现场新录一条。
        // 已有记录必须持行锁读取，并校验页面选中时的版本号：
        //   - 与并发删除竞争：删除事务拿不到行锁，且删除前服务端会拦截未交付引用，绝不会留下悬空订单；
        //   - 与并发编辑竞争：版本不一致直接 409 要求重新选择，一致则冻结的就是编辑前被确认的那版尺寸。
        Measurement measurement = null;
        if (req.getMeasurementId() != null) {
            measurement = measurementRepository.findByIdForUpdate(req.getMeasurementId())
                    .orElseThrow(() -> new BizException("量体记录不存在或已被删除，请重新选择量体记录"));
            if (!measurement.getCustomerId().equals(customer.getId())) {
                throw new BizException("量体记录不属于该客户，请重新选择");
            }
            if (req.getMeasurementVersion() != null
                    && !req.getMeasurementVersion().equals(measurement.getVersion())) {
                throw new ConflictException("选中的量体记录「" + measurement.getRecordNo()
                        + "」在下单期间已被他人修改，请刷新量体列表并重新选择后再下单");
            }
        } else if (req.getMeasurement() != null) {
            MeasurementSaveReq msr = req.getMeasurement();
            msr.setCustomerId(customer.getId());
            measurement = measurementService.createForOrder(msr, customer);
        }
        if (measurement == null) {
            throw new BizException("请先绑定或录入量体记录");
        }

        BigDecimal fabricCost = fabric.getUnitPrice().multiply(usage).setScale(2, RoundingMode.HALF_UP);
        BigDecimal craftFee = style.getBasePrice().setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalPrice = fabricCost.add(craftFee).setScale(2, RoundingMode.HALF_UP);
        BigDecimal deposit = totalPrice.multiply(GarmentDict.DEPOSIT_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal balance = totalPrice.subtract(deposit).setScale(2, RoundingMode.HALF_UP);

        CustomOrder order = new CustomOrder();
        order.setOrderNo(nextOrderNo());
        order.setCustomerId(customer.getId());
        order.setStyleId(style.getId());
        order.setFabricId(fabric.getId());
        order.setMeasurementId(measurement.getId());
        order.setUsageMeters(usage);
        order.setOrderDate(today);
        order.setDeliveryDate(delivery);
        order.setFabricCost(fabricCost);
        order.setCraftFee(craftFee);
        order.setTotalPrice(totalPrice);
        order.setDeposit(deposit);
        order.setBalance(balance);
        order.setDepositPaid(Boolean.TRUE.equals(req.getPayDeposit()));
        order.setBalancePaid(Boolean.FALSE);
        order.setStatus("ORDERED");
        order.setRemark(req.getRemark());
        CustomOrder saved = orderRepository.save(order);
        // 订单行落库后立刻在同一事务内冻结完整量体快照；任一步失败整体回滚，
        // 不会出现「订单已创建却快照只写了一半」的中间态。
        snapshotService.freezeForNewOrder(saved, measurement);

        // 扣减面料库存 + 按标准工序自动生成待开工工单
        fabricRepository.restock(fabric.getId(), usage.negate());
        workOrderService.generateForOrder(saved);
        return saved;
    }

    // ---------------- 状态流转 ----------------

    @Transactional
    public CustomOrder advance(Long id, OrderAdvanceReq req) {
        // 持订单行锁推进：与整改台的返修/复核/放行串行，杜绝「绕过页面」与并发造成的互相矛盾
        CustomOrder order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("订单不存在"));
        String current = order.getStatus();
        String requested = req == null ? null : req.getTargetStatus();
        String next = requested == null || requested.isBlank()
                ? GarmentDict.nextOrderStatus(current) : requested;
        if (next == null) {
            throw new BizException("订单已交付，不能再推进");
        }
        if (GarmentDict.orderStatusIndex(next) < 0) {
            throw new BizException("目标订单状态不合法：" + next);
        }
        if (GarmentDict.orderStatusIndex(next) != GarmentDict.orderStatusIndex(current) + 1) {
            throw new BizException("订单必须逐级流转：" + GarmentDict.orderStatusName(current) + " → "
                    + GarmentDict.orderStatusName(GarmentDict.nextOrderStatus(current)));
        }
        checkPrerequisite(order, next);

        if ("FINISHED".equals(next)) {
            // 阻断问题必须全关闭、返修工单必须全部收口；普通问题未关闭须显式确认放行并逐条留痕
            List<FittingIssue> pendingMinor = fittingIssueService.checkCanFinish(order);
            if (!pendingMinor.isEmpty() && !Boolean.TRUE.equals(req == null ? null : req.getConfirmRelease())) {
                throw new BizException("还有 " + pendingMinor.size()
                        + " 个普通试衣问题未关闭，必须逐条复核关闭，或明确确认随单放行（写明原因）后才能完工");
            }
            // 无论是否有放行，完工收口都在整改流水里留痕（操作人/时间/前后状态）
            fittingIssueService.releaseAndLogOnFinish(order,
                    req == null ? null : req.getOperator(),
                    req == null ? null : req.getReleaseReason());
        }

        orderRepository.updateStatus(id, next);
        order.setStatus(next);
        return order;
    }

    private void checkPrerequisite(CustomOrder order, String next) {
        // 任何状态推进前，尺寸事实必须可用：待核对（原量体记录缺失）的订单禁止推进，
        // 必须先在订单看板完成人工核对，杜绝拿不到尺寸的订单流入裁剪/缝制。
        OrderMeasurementSnapshotDto snap = snapshotService.getOrBackfill(order.getId());
        if (snap.isPendingVerify()) {
            throw new BizException("该订单量体尺寸待核对（原量体记录已缺失），请先在订单看板人工核对补齐后再推进");
        }
        switch (next) {
            case "MEASURED" -> {
                if (order.getMeasurementId() == null) {
                    throw new BizException("尚未关联量体记录，不能流转到「已量体」");
                }
            }
            case "CUT" -> {
                long cutting = workOrderRepository.countByOrderIdAndStep(order.getId(), "CUTTING");
                if (cutting == 0) {
                    throw new BizException("尚未派发裁剪工单，不能流转到「已裁剪」");
                }
                long doneCnt = workOrderRepository.findByOrderIdOrderByIdAsc(order.getId()).stream()
                        .filter(w -> "CUTTING".equals(w.getStep()) && "DONE".equals(w.getStatus()))
                        .count();
                if (doneCnt < cutting) {
                    throw new BizException("裁剪工单尚未全部完成（" + doneCnt + "/" + cutting + "），不能流转到「已裁剪」");
                }
            }
            case "SEWING" -> {
                boolean started = workOrderRepository.findByOrderIdOrderByIdAsc(order.getId()).stream()
                        .anyMatch(w -> "SEWING".equals(w.getStep())
                                && ("RUNNING".equals(w.getStatus()) || "DONE".equals(w.getStatus())));
                if (!started) {
                    throw new BizException("缝制工单尚未开工，不能流转到「缝制中」");
                }
            }
            case "FITTING" -> {
                boolean sewn = workOrderRepository.findByOrderIdOrderByIdAsc(order.getId()).stream()
                        .filter(w -> "SEWING".equals(w.getStep()))
                        .allMatch(w -> "DONE".equals(w.getStatus()));
                boolean hasSewing = workOrderRepository.countByOrderIdAndStep(order.getId(), "SEWING") > 0;
                if (!hasSewing || !sewn) {
                    throw new BizException("缝制工单尚未全部完成，不能流转到「试衣」");
                }
            }
            case "FINISHED" -> {
                List<com.garment.factory.domain.WorkOrder> all = workOrderRepository
                        .findByOrderIdOrderByIdAsc(order.getId());
                long unfinished = all.stream().filter(w -> !"DONE".equals(w.getStatus())).count();
                if (all.isEmpty()) {
                    throw new BizException("该订单还没有任何工序工单，不能流转到「已完成」");
                }
                if (unfinished > 0) {
                    throw new BizException("还有 " + unfinished + " 张工序工单未完成，不能流转到「已完成」");
                }
                // 试衣整改闸口的阻断/返修校验在 advance 中给出，这里不重复，保证服务端任何入口都绕不过
            }
            case "DELIVERED" -> {
                if (!Boolean.TRUE.equals(order.getBalancePaid())) {
                    throw new BizException("尾款尚未结清，不能交付");
                }
            }
            default -> {
            }
        }
    }

    @Transactional
    public CustomOrder payBalance(Long id) {
        CustomOrder order = orderRepository.findById(id).orElseThrow(() -> new BizException("订单不存在"));
        if (Boolean.TRUE.equals(order.getBalancePaid())) {
            throw new BizException("尾款已经结清了");
        }
        orderRepository.payBalance(id);
        order.setBalancePaid(Boolean.TRUE);
        return order;
    }

    @Transactional
    public void remove(Long id) {
        CustomOrder order = orderRepository.findById(id).orElseThrow(() -> new BizException("订单不存在"));
        if (!"ORDERED".equals(order.getStatus())) {
            throw new BizException("只有「已下单」状态的新订单可以撤销");
        }
        // 归还面料库存
        fabricRepository.restock(order.getFabricId(), order.getUsageMeters());
        List<com.garment.factory.domain.WorkOrder> workOrders = workOrderRepository
                .findByOrderIdOrderByIdAsc(id);
        if (!workOrders.isEmpty()) {
            workOrderRepository.deleteAll(workOrders);
        }
        // 撤销下单：冻结快照一并删除（量体记录本身保留，仍可被其它订单/重新下单使用）
        orderSnapshotRepository.findByOrderId(id).ifPresent(orderSnapshotRepository::delete);
        orderRepository.delete(order);
    }

    // ---------------- 辅助 ----------------

    private String nextOrderNo() {
        long seq = orderRepository.count() + 1;
        String no;
        do {
            no = String.format("ORD-2026%04d", seq);
            seq++;
        } while (orderRepository.existsByOrderNo(no));
        return no;
    }

    private Style requireStyle(Long id) {
        if (id == null) {
            throw new BizException("请先选择款式");
        }
        return styleRepository.findById(id).orElseThrow(() -> new BizException("款式不存在"));
    }

    private Fabric requireFabric(Long id) {
        if (id == null) {
            throw new BizException("请先选择面料");
        }
        return fabricRepository.findById(id).orElseThrow(() -> new BizException("面料不存在"));
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
