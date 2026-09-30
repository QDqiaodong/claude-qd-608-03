package com.garment.factory.service.cut;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.garment.factory.common.BizException;
import com.garment.factory.common.ConflictException;
import com.garment.factory.domain.CutKit;
import com.garment.factory.domain.CutKitDict;
import com.garment.factory.domain.CutKitEvent;
import com.garment.factory.domain.CutKitException;
import com.garment.factory.domain.CutKitIdempotency;
import com.garment.factory.domain.CutKitPiece;
import com.garment.factory.dto.CutKitActionReq;
import com.garment.factory.dto.CutKitCreateReq;
import com.garment.factory.dto.CutKitManifestReq;
import com.garment.factory.dto.CutKitPieceItemReq;
import com.garment.factory.dto.CutKitScanReq;
import com.garment.factory.dto.CutPieceDiff;
import com.garment.factory.dto.SliceView;
import com.garment.factory.repository.CutKitEventRepository;
import com.garment.factory.repository.CutKitExceptionRepository;
import com.garment.factory.repository.CutKitIdempotencyRepository;
import com.garment.factory.repository.CutKitPieceRepository;
import com.garment.factory.repository.CutKitRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 裁片套件封包交接服务。
 *
 * 并发与一致性约定：
 *  1. 所有写流程在同一事务内先 {@code findByIdForUpdate}（SELECT ... FOR UPDATE）锁住套件行，
 *     同套件的封包/拆包/交接/接收/异常/扫码全部串行化 —— 两个人同时交接或接收，只有一人成功，
 *     失败者看到最新状态后整体回滚，不留下半条交接记录或错误状态；
 *  2. 交接/接收支持客户端 requestId 幂等：uk_cki_key 唯一约束 + 事务内先查后插，
 *     重复扫码/重复请求重放首次结果；裁片条码由 uk_ckp_scan 唯一约束兜底；
 *  3. 状态机非法流转一律拒绝（尤其已交接套件不能回草稿）；
 *  4. 每次状态变化/差异/处理动作都向 cut_kit_event 追加一条事件（只追加，无更新删除入口）。
 */
@Service
public class CutKitService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final CutKitRepository kitRepository;
    private final CutKitPieceRepository pieceRepository;
    private final CutKitExceptionRepository exceptionRepository;
    private final CutKitEventRepository eventRepository;
    private final CutKitIdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final KitEventRecorder eventRecorder;

    /**
     * 幂等并发的进程内兜底：同一 idemKey 的两个请求同时到达本实例时，
     * 让第二个在同一 JVM 上等第一个提交完再走「查已有记录重放」路径（数据库唯一索引是跨实例总闸）。
     */
    private final Map<String, Object> idemLocks = new ConcurrentHashMap<>();

    public CutKitService(CutKitRepository kitRepository, CutKitPieceRepository pieceRepository,
                         CutKitExceptionRepository exceptionRepository, CutKitEventRepository eventRepository,
                         CutKitIdempotencyRepository idempotencyRepository, ObjectMapper objectMapper,
                         KitEventRecorder eventRecorder) {
        this.kitRepository = kitRepository;
        this.pieceRepository = pieceRepository;
        this.exceptionRepository = exceptionRepository;
        this.eventRepository = eventRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.eventRecorder = eventRecorder;
    }

    // ================================================================
    // 列表 / 详情 / 时间线
    // ================================================================

    @Transactional(readOnly = true)
    public SliceView<Map<String, Object>> list(String status, String keyword, int page, int size) {
        Slice<CutKit> slice = kitRepository.sliceBy(trim(status), trim(keyword), PageRequest.of(page, size));
        List<Map<String, Object>> content = slice.getContent().stream().map(this::kitSummary).toList();
        return SliceView.of(slice.map(k -> kitSummary(k)));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        CutKit kit = requireKit(id);
        return buildDetail(kit);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> timeline(Long id) {
        requireKit(id);
        return eventRepository.findByKitIdOrderByIdAsc(id).stream().map(this::eventView).toList();
    }

    private Map<String, Object> buildDetail(CutKit kit) {
        Map<String, Object> view = kitSummary(kit);
        List<CutKitPiece> all = pieceRepository.findByKitIdOrderByPhaseAscIdAsc(kit.getId());
        Map<String, List<Map<String, Object>>> byPhase = new LinkedHashMap<>();
        byPhase.put(CutKitDict.PHASE_EXPECTED, new ArrayList<>());
        byPhase.put(CutKitDict.PHASE_PACKING, new ArrayList<>());
        byPhase.put(CutKitDict.PHASE_RECEIVING, new ArrayList<>());
        byPhase.put(CutKitDict.PHASE_RESOLVE, new ArrayList<>());
        for (CutKitPiece p : all) {
            byPhase.computeIfAbsent(p.getPhase(), k -> new ArrayList<>()).add(pieceView(p));
        }
        view.put("manifest", byPhase.get(CutKitDict.PHASE_EXPECTED));
        view.put("packingScans", byPhase.get(CutKitDict.PHASE_PACKING));
        view.put("receivingScans", byPhase.get(CutKitDict.PHASE_RECEIVING));
        view.put("resolveScans", byPhase.get(CutKitDict.PHASE_RESOLVE));
        view.put("exceptions",
                exceptionRepository.findByKitIdOrderByIdDesc(kit.getId()).stream().map(this::exceptionView).toList());
        view.put("latestValidation", computePhaseValidation(kit.getId(), CutKitDict.PHASE_PACKING));
        return view;
    }

    // ================================================================
    // 建档 / 清单 / 扫码（草稿态）
    // ================================================================

    @Transactional
    public Map<String, Object> create(CutKitCreateReq req) {
        LocalDateTime now = LocalDateTime.now();
        CutKit kit = new CutKit();
        kit.setKitNo(generateKitNo(now));
        kit.setOrderNo(req.getOrderNo().trim());
        kit.setCutBatchNo(req.getCutBatchNo().trim());
        kit.setGarmentName(req.getGarmentName().trim());
        kit.setSizeMark(req.getSizeMark().trim());
        kit.setStatus(CutKitDict.STATUS_DRAFT);
        kit.setCreatedBy(req.getOperator().trim());
        kit.setCreatedAt(now);
        kit.setUpdatedAt(now);
        kit = kitRepository.save(kit);

        appendEvent(kit, CutKitDict.EVT_CREATE, kit.getStatus(), kit.getStatus(), req.getOperator().trim(),
                Map.of("orderNo", kit.getOrderNo(), "cutBatchNo", kit.getCutBatchNo(),
                        "garmentName", kit.getGarmentName(), "sizeMark", kit.getSizeMark()),
                null, snapshot(kit));
        return buildDetail(kit);
    }

    /** 整单替换应有清单（仅草稿态）。封包后清单冻结。 */
    @Transactional
    public Map<String, Object> saveManifest(Long id, CutKitManifestReq req) {
        CutKit kit = lockKit(id);
        requireStatus(kit, CutKitDict.STATUS_DRAFT, "只有草稿态套件才能修改清单，封包后请先拆包");

        // 服务端校验清单内容与自洽性（部位/侧/批次/尺码）
        Map<String, CutKitValidator.ExpectedGroup> groups = CutKitValidator.indexManifest(req.getItems());
        int total = groups.values().stream().mapToInt(g -> g.expectedQty).sum();

        Map<String, Object> before = snapshot(kit);
        pieceRepository.deleteByKitIdAndPhase(id, CutKitDict.PHASE_EXPECTED);
        pieceRepository.flush();
        List<CutKitPiece> rows = new ArrayList<>();
        for (CutKitPieceItemReq item : req.getItems()) {
            CutKitPiece p = new CutKitPiece();
            p.setKitId(id);
            p.setPhase(CutKitDict.PHASE_EXPECTED);
            p.setPart(CutKitValidator.normalizePart(item.getPart()));
            p.setSide(CutKitValidator.normalizeSide(item.getSide()));
            p.setFabricBatchNo(item.getFabricBatchNo().trim());
            p.setSizeMark(item.getSizeMark().trim());
            p.setQuantity(item.getQuantity());
            p.setScannedBy(req.getOperator().trim());
            p.setScannedAt(LocalDateTime.now());
            rows.add(p);
        }
        pieceRepository.saveAll(rows);
        kit.setExpectedTotal(total);
        kit.setUpdatedAt(LocalDateTime.now());

        appendEvent(kit, CutKitDict.EVT_UPDATE_MANIFEST, CutKitDict.STATUS_DRAFT, CutKitDict.STATUS_DRAFT,
                req.getOperator().trim(),
                Map.of("itemKinds", groups.size(), "totalPieces", total,
                        "reason", req.getReason() == null ? "" : req.getReason()),
                before, snapshot(kit));
        return buildDetail(kit);
    }

    /** 逐片扫码（仅草稿态，入 PACKING 阶段）。重复条码幂等忽略，不重复入账。 */
    @Transactional
    public Map<String, Object> scanPieces(Long id, CutKitScanReq req) {
        CutKit kit = lockKit(id);
        requireStatus(kit, CutKitDict.STATUS_DRAFT, "只有草稿态套件才能扫码，封包后实际片记录不可改动");
        int added = persistScans(id, CutKitDict.PHASE_PACKING, req.getOperator().trim(), req.getPieces(),
                countByPhase(id, CutKitDict.PHASE_PACKING));
        kit.setUpdatedAt(LocalDateTime.now());
        appendEvent(kit, CutKitDict.EVT_SCAN_PIECE, CutKitDict.STATUS_DRAFT, CutKitDict.STATUS_DRAFT,
                req.getOperator().trim(),
                Map.of("submitted", req.getPieces().size(), "added", added), null, snapshot(kit));
        return buildDetail(kit);
    }

    /** 移除一片误扫的实际片（仅草稿态），移除动作也进时间线 */
    @Transactional
    public Map<String, Object> removeScan(Long id, Long pieceId, String operator) {
        CutKit kit = lockKit(id);
        requireStatus(kit, CutKitDict.STATUS_DRAFT, "只有草稿态套件才能移除扫码记录");
        CutKitPiece piece = pieceRepository.findById(pieceId)
                .orElseThrow(() -> new BizException("扫码记录不存在"));
        if (!piece.getKitId().equals(id) || !CutKitDict.PHASE_PACKING.equals(piece.getPhase())) {
            throw new BizException("只能移除本套件封包阶段的扫码记录");
        }
        Map<String, Object> before = snapshot(kit);
        pieceRepository.delete(piece);
        kit.setUpdatedAt(LocalDateTime.now());
        appendEvent(kit, CutKitDict.EVT_REMOVE_SCAN, CutKitDict.STATUS_DRAFT, CutKitDict.STATUS_DRAFT,
                operator == null || operator.isBlank() ? "裁剪人员" : operator.trim(),
                Map.of("pieceCode", nz(piece.getPieceCode()), "part", piece.getPart(), "side", piece.getSide()),
                before, snapshot(kit));
        return buildDetail(kit);
    }

    // ================================================================
    // 封包校验 / 封包 / 拆包
    // ================================================================

    /** 只做校验不改状态：返回缺片/重复/混放/批次/尺码全部差异，供页面随扫随看 */
    @Transactional(readOnly = true)
    public Map<String, Object> validate(Long id) {
        CutKit kit = requireKit(id);
        Map<String, Object> result = computePhaseValidation(id, CutKitDict.PHASE_PACKING);
        result.put("status", kit.getStatus());
        result.put("sealable",
                Boolean.TRUE.equals(result.get("passed")) && CutKitDict.STATUS_DRAFT.equals(kit.getStatus()));
        return result;
    }

    /** 封包：校验完全一致才成功；任何差异都拒绝（封包不落库），拦截事件用独立事务留痕 */
    @Transactional
    public Map<String, Object> seal(Long id, CutKitActionReq req) {
        CutKit kit = lockKit(id);
        requireStatus(kit, CutKitDict.STATUS_DRAFT, "只有草稿态套件可以封包");

        ValidationResult vr = validatePhase(id, CutKitDict.PHASE_PACKING);
        if (!vr.passed()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("passed", false);
            detail.put("expectedTotal", vr.expectedTotal());
            detail.put("actualTotal", vr.actualTotal());
            detail.put("diffCount", vr.diffs().size());
            detail.put("diffs", vr.diffs());
            eventRecorder.record(id, CutKitDict.EVT_VALIDATE, CutKitDict.STATUS_DRAFT, CutKitDict.STATUS_DRAFT,
                    req.getOperator().trim(), detail);
            throw new BizException("封包被拦截：实际裁片与清单不一致（" + vr.diffs().size() + " 项差异），"
                    + "请补齐或剔除后再封包");
        }

        LocalDateTime now = LocalDateTime.now();
        String sealNo = kit.getKitNo() + "-SL" + now.format(NO_FMT);
        kit.setSealedAt(now);
        kit.setSealedBy(req.getOperator().trim());
        kit.setSealNo(sealNo);
        kit.setStatus(CutKitDict.STATUS_SEALED);
        kit.setUpdatedAt(now);
        appendEvent(kit, CutKitDict.EVT_SEAL, CutKitDict.STATUS_DRAFT, CutKitDict.STATUS_SEALED,
                req.getOperator().trim(),
                Map.of("sealNo", sealNo, "pieceTotal", vr.actualTotal(),
                        "expectedTotal", vr.expectedTotal()),
                null, snapshot(kit));
        return buildDetail(kit);
    }

    /** 拆包（仅已封包态）：必须填写原因，保留拆包前后完整快照；已交接不能拆、不能回草稿 */
    @Transactional
    public Map<String, Object> unseal(Long id, CutKitActionReq req) {
        CutKit kit = lockKit(id);
        requireStatus(kit, CutKitDict.STATUS_SEALED, "只有已封包、尚未交接的套件可以拆包");
        if (req.getReason() == null || req.getReason().isBlank()) {
            throw new BizException("拆包必须填写原因");
        }
        Map<String, Object> before = snapshot(kit);
        kit.setStatus(CutKitDict.STATUS_DRAFT);
        kit.setSealedAt(null);
        kit.setSealedBy(null);
        kit.setSealNo(null);
        kit.setUpdatedAt(LocalDateTime.now());
        appendEvent(kit, CutKitDict.EVT_UNSEAL, CutKitDict.STATUS_SEALED, CutKitDict.STATUS_DRAFT,
                req.getOperator().trim(), Map.of("reason", req.getReason().trim()), before, snapshot(kit));
        return buildDetail(kit);
    }

    // ================================================================
    // 交接 / 接收（幂等 + 悲观锁）
    // ================================================================

    /** 交接：已封包 → 已交接，记录交出人/指定接收人/扫描时间，重复请求幂等 */
    @Transactional
    public Map<String, Object> handOver(Long id, CutKitActionReq req) {
        return runIdempotent("HAND_OVER", id, req, idemKey -> {
            CutKit kit = lockKit(id);
            Map<String, Object> replay = replayIfPresent(idemKey);
            if (replay != null) {
                return replay;
            }
            if (CutKitDict.STATUS_HANDED_OVER.equals(kit.getStatus())
                    || CutKitDict.STATUS_RECEIVED.equals(kit.getStatus())
                    || CutKitDict.STATUS_EXCEPTION.equals(kit.getStatus())) {
                throw new ConflictException("套件已交接（当前状态：" + CutKitDict.statusName(kit.getStatus())
                        + "），重复交接无效");
            }
            requireStatus(kit, CutKitDict.STATUS_SEALED, "只有已封包套件可以交接");
            if (req.getReceiverName() == null || req.getReceiverName().isBlank()) {
                throw new BizException("交接必须登记接收人");
            }
            LocalDateTime now = LocalDateTime.now();
            kit.setStatus(CutKitDict.STATUS_HANDED_OVER);
            kit.setHandedBy(req.getOperator().trim());
            kit.setReceiverName(req.getReceiverName().trim());
            kit.setHandedOverAt(now);
            kit.setUpdatedAt(now);
            appendEvent(kit, CutKitDict.EVT_HAND_OVER, CutKitDict.STATUS_SEALED, CutKitDict.STATUS_HANDED_OVER,
                    req.getOperator().trim(),
                    Map.of("handedBy", kit.getHandedBy(), "receiverName", kit.getReceiverName(),
                            "sealNo", nz(kit.getSealNo())),
                    null, snapshot(kit));
            return buildDetail(kit);
        });
    }

    /**
     * 接收：已交接 → 已接收。接收方必须核对封签并逐片清点裁片数量与内容。
     *  - 封签不符 / 数量不符 / 漏片混放批次尺码差异：本次接收整体回滚（状态保持已交接，
     *    不产生半条接收记录或错误状态），差异与处理人通过独立事务写入 RECEIVE_FAIL 事件；
     *    是否进入异常流程由接收方随后显式「登记异常」决定；
     *  - 核对全部通过：写入 RECEIVING 清点凭证并置为已接收。
     */
    @Transactional
    public Map<String, Object> receive(Long id, CutKitActionReq req) {
        return runIdempotent("RECEIVE", id, req, idemKey -> {
            CutKit kit = lockKit(id);
            Map<String, Object> replay = replayIfPresent(idemKey);
            if (replay != null) {
                return replay;
            }
            if (CutKitDict.STATUS_RECEIVED.equals(kit.getStatus())) {
                throw new ConflictException("套件已接收，重复接收无效（接收人：" + nz(kit.getReceivedBy()) + "）");
            }
            if (CutKitDict.STATUS_EXCEPTION.equals(kit.getStatus())) {
                throw new ConflictException("套件处于异常处理中，须补齐复核后才能接收");
            }
            requireStatus(kit, CutKitDict.STATUS_HANDED_OVER, "只有已交接、待接收的套件可以接收确认");

            // 1) 封签核对：不符整体回滚，独立事务保留 RECEIVE_FAIL 失败事件（状态保持已交接）
            if (req.getSealNo() == null || req.getSealNo().isBlank()) {
                throw new BizException("接收必须核对并填写封签编号");
            }
            if (!req.getSealNo().trim().equals(kit.getSealNo())) {
                recordFailure(kit, req.getOperator().trim(),
                        Map.of("reason", "封签编号不符：封包封签 " + nz(kit.getSealNo())
                                + "，接收方扫到 " + req.getSealNo().trim(),
                                "scannedSealNo", req.getSealNo().trim(),
                                "expectedSealNo", nz(kit.getSealNo()),
                                "diffs", List.of()));
                throw new ConflictException("接收复核失败：封签编号与封包不一致，已拒绝接收（状态保持已交接）");
            }

            // 2) 裁片数量与逐片内容核对（接收方必须整轮重扫，缺一片都算复核失败）
            List<CutKitScanReq.ScanItem> scanned = req.getPieces();
            if (scanned == null || scanned.isEmpty()) {
                throw new BizException("接收必须再次逐片清点裁片，请扫码复核（封签已核对通过）");
            }
            if (kit.getExpectedTotal() != null && scanned.size() != kit.getExpectedTotal()) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("reason", "裁片数量不符：清单 " + kit.getExpectedTotal() + " 片，本次清点 "
                        + scanned.size() + " 片");
                detail.put("expectedCount", kit.getExpectedTotal());
                detail.put("actualCount", scanned.size());
                detail.put("diffs", List.of());
                recordFailure(kit, req.getOperator().trim(), detail);
                throw new ConflictException("接收复核失败：裁片数量不符（清单 " + kit.getExpectedTotal()
                        + " 片，清点 " + scanned.size() + " 片），本次接收已整体回滚");
            }
            List<CutKitPiece> manifestRows =
                    pieceRepository.findByKitIdAndPhaseOrderByIdAsc(id, CutKitDict.PHASE_EXPECTED);
            List<CutPieceDiff> diffs = CutKitValidator.compare(toItemReqs(manifestRows), scanned);
            if (!diffs.isEmpty()) {
                // 漏片/混片/批次/尺码：接收不成立，主事务回滚，差异由独立事务写入 RECEIVE_FAIL 事件。
                // 后续由接收方在页面上显式「登记异常」进入异常流程（不能静默改状态）。
                recordFailure(kit, req.getOperator().trim(),
                        Map.of("reason", "逐片复核发现 " + diffs.size() + " 项差异，接收回滚（状态保持已交接）",
                                "expectedCount", kit.getExpectedTotal(),
                                "actualCount", scanned.size(),
                                "diffs", diffs));
                throw new ConflictException("接收复核失败：实际裁片与清单存在 " + diffs.size()
                        + " 项差异，本次接收已整体回滚，请登记异常处理后再接收");
            }
            // 核对通过：本轮 RECEIVING 重扫记录作为接收凭证落库
            persistTransientScans(id, CutKitDict.PHASE_RECEIVING, req.getOperator().trim(), scanned);

            // 3) 核对通过 → 已接收
            LocalDateTime now = LocalDateTime.now();
            String from = kit.getStatus();
            kit.setStatus(CutKitDict.STATUS_RECEIVED);
            kit.setReceivedBy(req.getOperator().trim());
            kit.setReceivedAt(now);
            kit.setUpdatedAt(now);
            appendEvent(kit, CutKitDict.EVT_RECEIVE, from, CutKitDict.STATUS_RECEIVED,
                    req.getOperator().trim(),
                    Map.of("receivedBy", kit.getReceivedBy(), "sealNo", nz(kit.getSealNo()),
                            "pieceTotal", scanned.size()),
                    null, snapshot(kit));
            return buildDetail(kit);
        });
    }

    // ================================================================
    // 异常：登记 / 补扫 / 复核关闭
    // ================================================================

    /** 主动登记异常（仅已交接态）：发现漏片/混片时套件转 EXCEPTION */
    @Transactional
    public Map<String, Object> raiseException(Long id, CutKitActionReq req) {
        CutKit kit = lockKit(id);
        requireStatus(kit, CutKitDict.STATUS_HANDED_OVER, "只有已交接、待接收的套件可以登记异常");
        if (req.getReason() == null || req.getReason().isBlank()) {
            throw new BizException("登记异常必须填写情况说明");
        }
        List<CutPieceDiff> diffs = List.of();
        if (req.getPieces() != null && !req.getPieces().isEmpty()) {
            List<CutKitPiece> manifestRows =
                    pieceRepository.findByKitIdAndPhaseOrderByIdAsc(id, CutKitDict.PHASE_EXPECTED);
            diffs = CutKitValidator.compare(toItemReqs(manifestRows), req.getPieces());
        }
        raiseExceptionInternal(kit, req, req.getReason().trim(), diffs);
        return buildDetail(kit);
    }

    /** 异常处理阶段逐片补扫（仅异常态，入 RESOLVE 阶段） */
    @Transactional
    public Map<String, Object> resolveScans(Long id, CutKitScanReq req) {
        CutKit kit = lockKit(id);
        requireStatus(kit, CutKitDict.STATUS_EXCEPTION, "只有异常态套件可以补扫裁片");
        // 补齐复核以「整轮重扫」为准：新一轮补扫前清空上一轮 RESOLVE 记录（异常事件里已存差异留痕）
        pieceRepository.deleteByKitIdAndPhase(id, CutKitDict.PHASE_RESOLVE);
        pieceRepository.flush();
        int added = persistScans(id, CutKitDict.PHASE_RESOLVE, req.getOperator().trim(), req.getPieces(), 0);
        kit.setUpdatedAt(LocalDateTime.now());
        appendEvent(kit, CutKitDict.EVT_SCAN_PIECE, CutKitDict.STATUS_EXCEPTION, CutKitDict.STATUS_EXCEPTION,
                req.getOperator().trim(),
                Map.of("phase", CutKitDict.PHASE_RESOLVE, "submitted", req.getPieces().size(), "added", added),
                null, snapshot(kit));
        return buildDetail(kit);
    }

    /** 异常复核：补齐后的 RESOLVE 重扫与清单完全一致才能关闭异常单、套件回到已交接 */
    @Transactional
    public Map<String, Object> resolveReview(Long id, CutKitActionReq req) {
        CutKit kit = lockKit(id);
        requireStatus(kit, CutKitDict.STATUS_EXCEPTION, "只有异常态套件可以复核关闭");
        if (req.getReason() == null || req.getReason().isBlank()) {
            throw new BizException("异常复核必须填写处理说明");
        }
        List<CutKitPiece> resolveRows =
                pieceRepository.findByKitIdAndPhaseOrderByIdAsc(id, CutKitDict.PHASE_RESOLVE);
        if (resolveRows.isEmpty()) {
            throw new BizException("尚未逐片补扫，无法复核；请补齐裁片并整轮重扫后再复核");
        }
        List<CutKitPiece> manifestRows =
                pieceRepository.findByKitIdAndPhaseOrderByIdAsc(id, CutKitDict.PHASE_EXPECTED);
        List<CutKitScanReq.ScanItem> scanned = resolveRows.stream().map(this::toScanItem).toList();
        List<CutPieceDiff> diffs = CutKitValidator.compare(toItemReqs(manifestRows), scanned);
        if (!diffs.isEmpty()) {
            appendEvent(kit, CutKitDict.EVT_VALIDATE, CutKitDict.STATUS_EXCEPTION, CutKitDict.STATUS_EXCEPTION,
                    req.getOperator().trim(),
                    Map.of("phase", CutKitDict.PHASE_RESOLVE, "passed", false, "diffCount", diffs.size(),
                            "diffs", diffs),
                    null, null);
            throw new BizException("复核未通过：补齐后的裁片与清单仍有 " + diffs.size() + " 项差异");
        }

        CutKitException ex = exceptionRepository.findByExceptionNo(kit.getExceptionNo())
                .orElseThrow(() -> new BizException("异常单不存在，数据异常"));
        LocalDateTime now = LocalDateTime.now();
        ex.setStatus("RESOLVED");
        ex.setResolvedBy(req.getOperator().trim());
        ex.setResolvedAt(now);
        ex.setResolveNote(req.getReason().trim());

        kit.setStatus(CutKitDict.STATUS_HANDED_OVER);
        kit.setExceptionNo(null);
        kit.setUpdatedAt(now);
        appendEvent(kit, CutKitDict.EVT_RESOLVE_EXCEPTION, CutKitDict.STATUS_EXCEPTION,
                CutKitDict.STATUS_HANDED_OVER, req.getOperator().trim(),
                Map.of("exceptionNo", ex.getExceptionNo(), "resolveNote", ex.getResolveNote(),
                        "pieceTotal", resolveRows.size()),
                null, snapshot(kit));
        return buildDetail(kit);
    }

    // ================================================================
    // 内部实现
    // ================================================================

    /**
     * 幂等执行模板。
     * 并发正确性靠两层：
     *  - 业务动作内部对套件行 SELECT ... FOR UPDATE，两个并发请求在数据库层串行；
     *  - uk_cki_key 唯一索引是跨实例总闸，第二个事务在插入幂等记录时撞唯一键 → 重放首次结果。
     * 动作函数在锁到套件行后必须先调 {@link #replayIfPresent(String)}：
     * 并发重复请求等锁释放后即可重放首个已提交结果，而不是误判成「已交接」冲突；
     * 不同请求号的竞争才走状态机冲突（409）。
     * 业务失败不写幂等记录（不把失败缓存成永久结果）；仅成功后回放完全一致的响应体。
     */
    private Map<String, Object> runIdempotent(String action, Long kitId, CutKitActionReq req,
                                              java.util.function.Function<String, Map<String, Object>> actionFn) {
        String requestId = trim(req.getRequestId());
        if (requestId == null) {
            return actionFn.apply(null);
        }
        String idemKey = action + ":" + kitId + ":" + requestId;
        Object lock = idemLocks.computeIfAbsent(idemKey, k -> new Object());
        synchronized (lock) {
            try {
                // 快速路径：顺序重放（首请求已提交，记录可见）
                Map<String, Object> existing = replayIfPresent(idemKey);
                if (existing != null) {
                    return existing;
                }
                Map<String, Object> result = actionFn.apply(idemKey);
                CutKitIdempotency rec = new CutKitIdempotency();
                rec.setIdemKey(idemKey);
                rec.setKitId(kitId);
                rec.setAction(action);
                rec.setResponseJson(toJson(result));
                rec.setHttpStatus(200);
                rec.setCreatedAt(LocalDateTime.now());
                try {
                    idempotencyRepository.saveAndFlush(rec);
                } catch (DataIntegrityViolationException dup) {
                    // 并发下另一实例/事务已先提交：重放它的结果（本事务其他改动随之回滚）
                    return replay(idempotencyRepository.findByIdemKey(idemKey).orElseThrow(() -> dup));
                }
                return result;
            } finally {
                idemLocks.remove(idemKey);
            }
        }
    }

    /** 锁内重放检查：有已提交的同键结果则返回，否则 null（继续本次业务判断） */
    private Map<String, Object> replayIfPresent(String idemKey) {
        if (idemKey == null) {
            return null;
        }
        return idempotencyRepository.findByIdemKeyForUpdate(idemKey).map(this::replay).orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> replay(CutKitIdempotency rec) {
        try {
            return objectMapper.readValue(rec.getResponseJson(), Map.class);
        } catch (JsonProcessingException e) {
            throw new BizException("幂等响应回放失败：" + e.getOriginalMessage());
        }
    }

    /**
     * 失败留痕：业务主事务将回滚（状态不变、无半条接收记录），用 REQUIRES_NEW 独立事务
     * 先把 RECEIVE_FAIL 事件提交下来 —— 既保证回滚彻底，又保证失败事实与差异项不丢。
     */
    private void recordFailure(CutKit kit, String operator, Map<String, Object> detail) {
        eventRecorder.record(kit.getId(), CutKitDict.EVT_RECEIVE_FAIL, kit.getStatus(), kit.getStatus(),
                operator, detail);
    }

    /** 独立事务事件记录器：调用方事务回滚时，这里写入的审计事件仍然保留 */
    @org.springframework.stereotype.Component
    static class KitEventRecorder {
        private final CutKitEventRepository events;
        private final ObjectMapper objectMapper;

        KitEventRecorder(CutKitEventRepository events, ObjectMapper objectMapper) {
            this.events = events;
            this.objectMapper = objectMapper;
        }

        @org.springframework.transaction.annotation.Transactional(
                propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
        public void record(Long kitId, String eventType, String fromStatus, String toStatus,
                           String operator, Object detail) {
            CutKitEvent ev = new CutKitEvent();
            ev.setKitId(kitId);
            ev.setEventType(eventType);
            ev.setFromStatus(fromStatus);
            ev.setToStatus(toStatus);
            ev.setOperator(operator);
            ev.setDetailJson(writeJson(objectMapper, detail));
            ev.setOccurredAt(LocalDateTime.now());
            events.save(ev);
        }
    }

    /** 异常状态转换 + 异常单 + 事件（与登记操作同事务，原子可见） */
    private void raiseExceptionInternal(CutKit kit, CutKitActionReq req, String reason,
                                        List<CutPieceDiff> diffs) {
        LocalDateTime now = LocalDateTime.now();
        CutKitException ex = new CutKitException();
        ex.setExceptionNo("EX" + now.format(NO_FMT) + "-" + kit.getId() + "-" + (kit.getExceptionCount() + 1));
        ex.setKitId(kit.getId());
        ex.setReason(reason);
        ex.setDiscrepanciesJson(toJson(diffs));
        ex.setRaisedBy(req.getOperator().trim());
        ex.setRaisedAt(now);
        ex.setStatus("OPEN");
        ex = exceptionRepository.save(ex);

        String from = kit.getStatus();
        kit.setStatus(CutKitDict.STATUS_EXCEPTION);
        kit.setExceptionNo(ex.getExceptionNo());
        kit.setExceptionCount(kit.getExceptionCount() + 1);
        kit.setUpdatedAt(now);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("exceptionNo", ex.getExceptionNo());
        detail.put("reason", reason);
        detail.put("diffCount", diffs.size());
        detail.put("diffs", diffs);
        appendEvent(kit, CutKitDict.EVT_RAISE_EXCEPTION, from, CutKitDict.STATUS_EXCEPTION,
                req.getOperator().trim(), detail, null, snapshot(kit));
    }

    /**
     * 实际片落库（通用）：重复条码幂等跳过。
     * 先一次性查出本阶段已有条码并对入参自身去重，正常路径绝不触碰唯一索引；
     * 唯一索引只作为跨请求/跨实例并发的最后防线（由事务失败/重试处理，不在这里递归自救）。
     */
    private int persistScans(Long kitId, String phase, String operator, List<CutKitScanReq.ScanItem> items,
                             int startSeq) {
        java.util.Set<String> existing = new java.util.HashSet<>();
        for (CutKitPiece p : pieceRepository.findByKitIdAndPhaseOrderByIdAsc(kitId, phase)) {
            if (p.getPieceCode() != null) {
                existing.add(p.getPieceCode());
            }
        }
        java.util.Set<String> seenInBatch = new java.util.HashSet<>();
        int seq = startSeq;
        int added = 0;
        List<CutKitPiece> rows = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (CutKitScanReq.ScanItem item : items) {
            String code = item.getPieceCode().trim();
            if (!existing.add(code) || !seenInBatch.add(code)) {
                continue; // 库中已有或本批重复：幂等跳过
            }
            CutKitPiece p = new CutKitPiece();
            p.setKitId(kitId);
            p.setPhase(phase);
            p.setSeq(++seq);
            p.setPieceCode(code);
            p.setPart(CutKitValidator.normalizePart(item.getPart()));
            p.setSide(CutKitValidator.normalizeSide(item.getSide()));
            p.setFabricBatchNo(item.getFabricBatchNo().trim());
            p.setSizeMark(item.getSizeMark().trim());
            p.setQuantity(1);
            p.setScannedBy(operator);
            p.setScannedAt(now);
            rows.add(p);
            added++;
        }
        if (!rows.isEmpty()) {
            pieceRepository.saveAll(rows);
            pieceRepository.flush();
        }
        return added;
    }

    /** 接收/复核整轮重扫落库：新一轮覆盖该阶段上一轮记录，本轮记录作为接收/复核凭证 */
    private List<CutKitPiece> persistTransientScans(Long kitId, String phase, String operator,
                                                    List<CutKitScanReq.ScanItem> items) {
        pieceRepository.deleteByKitIdAndPhase(kitId, phase);
        pieceRepository.flush();
        List<CutKitPiece> rows = new ArrayList<>();
        int seq = countByPhase(kitId, phase);
        LocalDateTime now = LocalDateTime.now();
        for (CutKitScanReq.ScanItem item : items) {
            CutKitPiece p = new CutKitPiece();
            p.setKitId(kitId);
            p.setPhase(phase);
            p.setSeq(++seq);
            p.setPieceCode(item.getPieceCode().trim());
            p.setPart(CutKitValidator.normalizePart(item.getPart()));
            p.setSide(CutKitValidator.normalizeSide(item.getSide()));
            p.setFabricBatchNo(item.getFabricBatchNo().trim());
            p.setSizeMark(item.getSizeMark().trim());
            p.setQuantity(1);
            p.setScannedBy(operator);
            p.setScannedAt(now);
            rows.add(p);
        }
        return pieceRepository.saveAll(rows);
    }

    private int countByPhase(Long kitId, String phase) {
        return (int) pieceRepository.countByKitIdAndPhase(kitId, phase);
    }

    // ---------------- 校验结果构造 ----------------

    private record ValidationResult(boolean passed, int expectedTotal, int actualTotal, List<CutPieceDiff> diffs) {
    }

    private ValidationResult validatePhase(Long kitId, String scanPhase) {
        List<CutKitPiece> manifestRows =
                pieceRepository.findByKitIdAndPhaseOrderByIdAsc(kitId, CutKitDict.PHASE_EXPECTED);
        List<CutKitPiece> scanRows = pieceRepository.findByKitIdAndPhaseOrderByIdAsc(kitId, scanPhase);
        if (manifestRows.isEmpty()) {
            throw new BizException("套件清单为空，请先建立应有裁片清单");
        }
        List<CutKitScanReq.ScanItem> scanned = scanRows.stream().map(this::toScanItem).toList();
        List<CutPieceDiff> diffs = CutKitValidator.compare(toItemReqs(manifestRows), scanned);
        int expectedTotal = manifestRows.stream().mapToInt(CutKitPiece::getQuantity).sum();
        boolean passed = diffs.isEmpty() && scanned.size() == expectedTotal;
        return new ValidationResult(passed, expectedTotal, scanned.size(), diffs);
    }

    private Map<String, Object> computePhaseValidation(Long kitId, String phase) {
        List<CutKitPiece> manifestRows =
                pieceRepository.findByKitIdAndPhaseOrderByIdAsc(kitId, CutKitDict.PHASE_EXPECTED);
        List<CutKitPiece> scanRows = pieceRepository.findByKitIdAndPhaseOrderByIdAsc(kitId, phase);
        Map<String, Object> result = new LinkedHashMap<>();
        if (manifestRows.isEmpty()) {
            result.put("passed", false);
            result.put("expectedTotal", 0);
            result.put("actualTotal", scanRows.size());
            result.put("diffs", List.of());
            result.put("message", "清单为空");
            return result;
        }
        List<CutKitScanReq.ScanItem> scanned = scanRows.stream().map(this::toScanItem).toList();
        List<CutPieceDiff> diffs = CutKitValidator.compare(toItemReqs(manifestRows), scanned);
        int expectedTotal = manifestRows.stream().mapToInt(CutKitPiece::getQuantity).sum();
        boolean passed = diffs.isEmpty() && scanned.size() == expectedTotal;
        result.put("passed", passed);
        result.put("expectedTotal", expectedTotal);
        result.put("actualTotal", scanned.size());
        result.put("diffs", diffs);
        result.put("message", passed ? "实际裁片与清单完全一致" : "存在 " + diffs.size() + " 项差异");
        return result;
    }

    // ---------------- 视图 / 快照 / 事件 ----------------

    private CutKit lockKit(Long id) {
        return kitRepository.findByIdForUpdate(id).orElseThrow(() -> new BizException("套件不存在"));
    }

    private CutKit requireKit(Long id) {
        return kitRepository.findById(id).orElseThrow(() -> new BizException("套件不存在"));
    }

    private void requireStatus(CutKit kit, String expected, String hint) {
        if (!expected.equals(kit.getStatus())) {
            throw new BizException(hint + "（当前状态：" + CutKitDict.statusName(kit.getStatus()) + "）");
        }
    }

    private void appendEvent(CutKit kit, String type, String from, String to, String operator,
                             Object detail, Map<String, Object> before, Map<String, Object> after) {
        CutKitEvent ev = new CutKitEvent();
        ev.setKitId(kit.getId());
        ev.setEventType(type);
        ev.setFromStatus(from);
        ev.setToStatus(to);
        ev.setOperator(operator);
        ev.setDetailJson(detail == null ? null : toJson(detail));
        ev.setBeforeJson(before == null ? null : toJson(before));
        ev.setAfterJson(after == null ? null : toJson(after));
        ev.setOccurredAt(LocalDateTime.now());
        eventRepository.save(ev);
    }

    /** 套件完整快照：时间线中「拆包前后」「状态前后」留存的就是这份不可变 JSON */
    private Map<String, Object> snapshot(CutKit kit) {
        Map<String, Object> snap = kitSummary(kit);
        snap.put("manifest", pieceRepository
                .findByKitIdAndPhaseOrderByIdAsc(kit.getId(), CutKitDict.PHASE_EXPECTED)
                .stream().map(this::pieceView).toList());
        snap.put("packingScans", pieceRepository
                .findByKitIdAndPhaseOrderByIdAsc(kit.getId(), CutKitDict.PHASE_PACKING)
                .stream().map(this::pieceView).toList());
        snap.put("snapshotAt", LocalDateTime.now().toString());
        return snap;
    }

    private Map<String, Object> kitSummary(CutKit k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", k.getId());
        m.put("kitNo", k.getKitNo());
        m.put("orderNo", k.getOrderNo());
        m.put("cutBatchNo", k.getCutBatchNo());
        m.put("garmentName", k.getGarmentName());
        m.put("sizeMark", k.getSizeMark());
        m.put("status", k.getStatus());
        m.put("statusName", CutKitDict.statusName(k.getStatus()));
        m.put("expectedTotal", k.getExpectedTotal());
        m.put("sealedAt", k.getSealedAt());
        m.put("sealedBy", k.getSealedBy());
        m.put("sealNo", k.getSealNo());
        m.put("handedOverAt", k.getHandedOverAt());
        m.put("handedBy", k.getHandedBy());
        m.put("receiverName", k.getReceiverName());
        m.put("receivedAt", k.getReceivedAt());
        m.put("receivedBy", k.getReceivedBy());
        m.put("exceptionNo", k.getExceptionNo());
        m.put("exceptionCount", k.getExceptionCount());
        m.put("createdBy", k.getCreatedBy());
        m.put("createdAt", k.getCreatedAt());
        m.put("updatedAt", k.getUpdatedAt());
        m.put("version", k.getVersion());
        return m;
    }

    private Map<String, Object> pieceView(CutKitPiece p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("phase", p.getPhase());
        m.put("seq", p.getSeq());
        m.put("pieceCode", p.getPieceCode());
        m.put("part", p.getPart());
        m.put("partName", CutKitDict.partName(p.getPart()));
        m.put("side", p.getSide());
        m.put("sideName", CutKitDict.sides().get(p.getSide()));
        m.put("fabricBatchNo", p.getFabricBatchNo());
        m.put("sizeMark", p.getSizeMark());
        m.put("quantity", p.getQuantity());
        m.put("scannedBy", p.getScannedBy());
        m.put("scannedAt", p.getScannedAt());
        return m;
    }

    private Map<String, Object> exceptionView(CutKitException e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("exceptionNo", e.getExceptionNo());
        m.put("reason", e.getReason());
        m.put("discrepancies", readJsonList(e.getDiscrepanciesJson()));
        m.put("raisedBy", e.getRaisedBy());
        m.put("raisedAt", e.getRaisedAt());
        m.put("status", e.getStatus());
        m.put("resolvedBy", e.getResolvedBy());
        m.put("resolvedAt", e.getResolvedAt());
        m.put("resolveNote", e.getResolveNote());
        return m;
    }

    private Map<String, Object> eventView(CutKitEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("eventType", e.getEventType());
        m.put("eventName", CutKitDict.eventName(e.getEventType()));
        m.put("fromStatus", e.getFromStatus());
        m.put("toStatus", e.getToStatus());
        m.put("operator", e.getOperator());
        m.put("detail", readJson(e.getDetailJson()));
        m.put("before", readJson(e.getBeforeJson()));
        m.put("after", readJson(e.getAfterJson()));
        m.put("occurredAt", e.getOccurredAt());
        return m;
    }

    private List<CutKitPieceItemReq> toItemReqs(List<CutKitPiece> rows) {
        List<CutKitPieceItemReq> list = new ArrayList<>();
        for (CutKitPiece p : rows) {
            CutKitPieceItemReq r = new CutKitPieceItemReq();
            r.setPart(p.getPart());
            r.setSide(p.getSide());
            r.setFabricBatchNo(p.getFabricBatchNo());
            r.setSizeMark(p.getSizeMark());
            r.setQuantity(p.getQuantity());
            list.add(r);
        }
        return list;
    }

    private CutKitScanReq.ScanItem toScanItem(CutKitPiece p) {
        CutKitScanReq.ScanItem item = new CutKitScanReq.ScanItem();
        item.setPieceCode(p.getPieceCode());
        item.setPart(p.getPart());
        item.setSide(p.getSide());
        item.setFabricBatchNo(p.getFabricBatchNo());
        item.setSizeMark(p.getSizeMark());
        return item;
    }

    private String generateKitNo(LocalDateTime now) {
        String base = "CK" + now.format(NO_FMT);
        String no = base;
        int suffix = 1;
        while (kitRepository.existsByKitNo(no)) {
            no = base + "-" + (++suffix);
        }
        return no;
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private String toJson(Object o) {
        return writeJson(objectMapper, o);
    }

    static String writeJson(ObjectMapper mapper, Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new BizException("JSON 序列化失败：" + e.getOriginalMessage());
        }
    }

    private Object readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            return json;
        }
    }

    private Object readJsonList(String json) {
        return readJson(json);
    }
}
