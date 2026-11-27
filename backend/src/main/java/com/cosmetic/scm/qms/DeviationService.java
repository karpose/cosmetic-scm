package com.cosmetic.scm.qms;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.CapaSource;
import com.cosmetic.scm.domain.CapaStatus;
import com.cosmetic.scm.domain.CapaType;
import com.cosmetic.scm.domain.CaseStatus;
import com.cosmetic.scm.domain.DeviationArea;
import com.cosmetic.scm.domain.DeviationLevel;
import com.cosmetic.scm.domain.Role;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.InventoryService;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.production.MfgOrder;
import com.cosmetic.scm.production.MfgOrderRepository;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.CurrentUser;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 偏差与 CAPA。重大及以上的偏差必须有 CAPA，且全部验证有效后才能关闭；
 * 未关闭时关联批次不能放行（见 QualityService）。严重偏差只能由质量安全负责人关闭。
 */
@Service
@RequiredArgsConstructor
public class DeviationService {

    private final DeviationRepository deviations;
    private final CapaRepository capas;
    private final BatchRepository batches;
    private final MfgOrderRepository orders;
    private final InventoryService inventory;
    private final MasterLookup lookup;
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record DeviationReq(@NotNull(message = "请选择发生日期") LocalDate occurredDate,
                               @NotNull DeviationArea area,
                               @NotNull DeviationLevel level,
                               @Size(max = 40) String batchNo,
                               @NotBlank(message = "请填写标题") @Size(max = 200) String title,
                               @NotBlank(message = "请填写偏差描述") @Size(max = 1000) String description,
                               @Size(max = 500) String immediateAction,
                               @NotNull(message = "请选择调查期限") LocalDate dueDate) {
    }

    public record InvestigateReq(@NotBlank(message = "请填写根本原因") @Size(max = 1000) String rootCause) {
    }

    public record CloseReq(@NotBlank(message = "请填写结论和批次处置意见") @Size(max = 1000) String conclusion,
                           @NotBlank(message = "请输入签批密码") String password) {
    }

    public record CapaReq(@NotNull CapaSource sourceType,
                          @Size(max = 40) String sourceNo,
                          Long deviationId,
                          @NotNull CapaType actionType,
                          @NotBlank(message = "请填写措施内容") @Size(max = 1000) String description,
                          @NotBlank(message = "请填写负责人") @Size(max = 64) String owner,
                          @NotNull(message = "请选择完成期限") LocalDate dueDate) {
    }

    public record CapaDoneReq(@NotBlank(message = "请填写完成情况") @Size(max = 500) String note) {
    }

    public record CapaVerifyReq(boolean effective,
                                @NotBlank(message = "请填写验证结论") @Size(max = 500) String note,
                                @NotBlank(message = "请输入签批密码") String password) {
    }

    public record DeviationView(Long id, String deviationNo, LocalDate occurredDate, DeviationArea area, String areaLabel,
                                DeviationLevel level, String levelLabel, Long batchId, String batchNo, String itemName,
                                Long moId, String moNo, String title, String description, String immediateAction,
                                String rootCause, LocalDate dueDate, boolean overdue, CaseStatus status, String statusLabel,
                                String conclusion, String closedBy, LocalDateTime closedAt, String createdBy,
                                LocalDateTime createdAt) {
    }

    public record CapaView(Long id, String capaNo, CapaSource sourceType, String sourceLabel, String sourceNo,
                           Long deviationId, CapaType actionType, String actionLabel, String description, String owner,
                           LocalDate dueDate, boolean overdue, CapaStatus status, String statusLabel, String doneNote,
                           String doneBy, LocalDateTime doneAt, String verifyNote, String verifiedBy,
                           LocalDateTime verifiedAt, String createdBy, LocalDateTime createdAt) {
    }

    public record ClosureRow(Long itemId, String itemType, String itemTypeLabel, String refNo, String title,
                             String levelOrStatus, String ownerOrArea, String batchNo,
                             LocalDate dueDate, Long daysLeft, boolean blockingRelease, boolean dueSoon,
                             String riskLevel, String riskLabel,
                             String escalationAction) {
    }

    public record QmsClosureBoard(int totalCount, int overdueCount, int blockingCount,
                                  int verifyPendingCount, int dueSoonCount,
                                  List<ClosureRow> rows) {
    }

    // ---------- 偏差 ----------

    @Transactional(readOnly = true)
    public List<DeviationView> list() {
        return toViews(deviations.findTop300ByOrderByIdDesc());
    }

    /** 全部偏差，导出用。 */
    @Transactional(readOnly = true)
    public List<DeviationView> listAll() {
        return toViews(deviations.findAllByOrderByIdDesc());
    }

    @Transactional(readOnly = true)
    public List<DeviationView> open() {
        return toViews(deviations.findByStatus(CaseStatus.OPEN));
    }

    @Transactional(readOnly = true)
    public DeviationView getDeviation(Long id) {
        return single(deviations.findById(id).orElseThrow(() -> BizException.notFound("偏差")));
    }

    @Transactional
    public DeviationView create(DeviationReq req) {
        if (req.occurredDate().isAfter(time.today())) {
            throw new BizException("发生日期不能晚于今天");
        }
        Batch batch = req.batchNo() == null || req.batchNo().isBlank() ? null : inventory.batchByNo(req.batchNo());
        Deviation d = newDeviation(req.occurredDate(), req.area(), req.level(), req.title(), req.description(), req.dueDate());
        d.setBatchId(batch == null ? null : batch.getId());
        d.setImmediateAction(blankToNull(req.immediateAction()));
        deviations.save(d);
        audit.log("登记偏差", d.getDeviationNo(), req.level().getLabel() + "：" + d.getTitle()
                + (batch == null ? "" : "，批次 " + batch.getBatchNo()), d.getDescription());
        return single(d);
    }

    /** 批生产记录的过程检验超限时自动生成：此时还没有成品批次，先挂在生产单上，入库时补上批次。 */
    @Transactional
    public Deviation createForOrder(MfgOrder mo, String title, String description) {
        Deviation d = newDeviation(time.today(), DeviationArea.PRODUCTION, DeviationLevel.MAJOR, title, description, time.today().plusDays(15));
        d.setMoId(mo.getId());
        deviations.save(d);
        audit.log("登记偏差", d.getDeviationNo(), "批生产记录自动生成，" + mo.getMoNo() + "：" + title, description);
        return d;
    }

    /** 稳定性试验结果不合格时自动生成。挂在批次上：批次还没放行的，偏差关闭前放不了行。 */
    @Transactional
    public Deviation createForStudy(String studyNo, Long batchId, LocalDate occurred, String title, String description) {
        Deviation d = newDeviation(occurred, DeviationArea.QC, DeviationLevel.MAJOR, title, description, time.today().plusDays(15));
        d.setBatchId(batchId);
        deviations.save(d);
        audit.log("登记偏差", d.getDeviationNo(), "稳定性试验自动生成，" + studyNo + "：" + title, description);
        return d;
    }

    /** 批次检验不合格时自动生成，挂在批次上：偏差关闭前这批放不了行。 */
    @Transactional
    public Deviation createForInspection(String inspectionNo, Long batchId, LocalDate occurred, String title, String description) {
        Deviation d = newDeviation(occurred, DeviationArea.QC, DeviationLevel.MAJOR, title, description, time.today().plusDays(15));
        d.setBatchId(batchId);
        deviations.save(d);
        audit.log("登记偏差", d.getDeviationNo(), "检验不合格自动生成，" + inspectionNo + "：" + title, description);
        return d;
    }

    /** 贮存区域温湿度超限时自动生成（一次超限一份），不挂批次：区域里的批次由调查时评估。 */
    @Transactional
    public Deviation createForStorage(String excursionNo, DeviationLevel level, LocalDate occurred, String title, String description) {
        Deviation d = newDeviation(occurred, DeviationArea.WAREHOUSE, level, title, description, time.today().plusDays(15));
        deviations.save(d);
        audit.log("登记偏差", d.getDeviationNo(), "温湿度超限自动生成，" + excursionNo + "：" + title, description);
        return d;
    }

    /** 运输条件不符合（温度超出、要求记录却没记录、到货异常）时自动生成；只涉及一个批次时挂在批次上。 */
    @Transactional
    public Deviation createForTransport(String recordNo, DeviationLevel level, LocalDate occurred, Long batchId, String title, String description) {
        Deviation d = newDeviation(occurred, DeviationArea.TRANSPORT, level, title, description, time.today().plusDays(15));
        d.setBatchId(batchId);
        deviations.save(d);
        audit.log("登记偏差", d.getDeviationNo(), "运输条件不符合自动生成，" + recordNo + "：" + title, description);
        return d;
    }

    private Deviation newDeviation(LocalDate occurred, DeviationArea area, DeviationLevel level, String title, String description, LocalDate due) {
        Deviation d = new Deviation();
        d.setDeviationNo(docNo.next("DV", time.today()));
        d.setOccurredDate(occurred);
        d.setArea(area);
        d.setLevel(level);
        d.setTitle(title.trim());
        d.setDescription(description.trim());
        d.setDueDate(due);
        d.setStatus(CaseStatus.OPEN);
        d.setCreatedBy(currentUser.usernameOrSystem());
        d.setCreatedAt(time.now());
        return d;
    }

    @Transactional
    public DeviationView investigate(Long id, InvestigateReq req) {
        Deviation d = require(id);
        requireOpen(d);
        d.setRootCause(req.rootCause().trim());
        deviations.save(d);
        audit.log("偏差调查", d.getDeviationNo(), "根本原因：" + d.getRootCause());
        return single(d);
    }

    @Transactional
    public DeviationView close(Long id, CloseReq req, AppUser signer) {
        Deviation d = require(id);
        requireOpen(d);
        if (d.getRootCause() == null) {
            throw new BizException("请先填写调查结果（根本原因）");
        }
        if (d.getLevel() == DeviationLevel.CRITICAL && !signer.hasRole(Role.QSO)) {
            throw new BizException("严重偏差只能由质量安全负责人关闭");
        }
        List<Capa> list = capas.findByDeviationId(id);
        if (d.getLevel() != DeviationLevel.MINOR && list.isEmpty()) {
            throw new BizException(d.getLevel().getLabel() + "必须有纠正预防措施，请先添加 CAPA");
        }
        List<String> pending = list.stream().filter(c -> c.getStatus() != CapaStatus.VERIFIED).map(Capa::getCapaNo).toList();
        if (!pending.isEmpty()) {
            throw new BizException("还有 CAPA 没有验证完成：" + String.join("、", pending));
        }
        d.setStatus(CaseStatus.CLOSED);
        d.setConclusion(req.conclusion().trim());
        d.setClosedBy(signer.getDisplayName());
        d.setClosedAt(time.now());
        deviations.save(d);
        audit.log("关闭偏差", d.getDeviationNo(), "签批人 " + signer.getDisplayName(), d.getConclusion());
        return single(d);
    }

    // ---------- CAPA ----------

    @Transactional(readOnly = true)
    public List<CapaView> listCapas() {
        return capas.findTop300ByOrderByIdDesc().stream().map(this::toView).toList();
    }

    /** 全部 CAPA，导出用。 */
    @Transactional(readOnly = true)
    public List<CapaView> listAllCapas() {
        return capas.findAllByOrderByIdDesc().stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<CapaView> openCapas() {
        return capas.findByStatusIn(List.of(CapaStatus.OPEN, CapaStatus.DONE)).stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public CapaView getCapa(Long id) {
        return toView(capas.findById(id).orElseThrow(() -> BizException.notFound("CAPA")));
    }

    /** 偏差与 CAPA 超期闭环升级监控看板（V70）。 */
    @Transactional(readOnly = true)
    public QmsClosureBoard closureBoard() {
        LocalDate today = time.today();
        List<ClosureRow> rows = new java.util.ArrayList<>();
        int overdueCnt = 0;
        int blockingCnt = 0;
        int verifyPendingCnt = 0;
        int dueSoonCnt = 0;

        for (DeviationView d : open()) {
            long daysLeft = java.time.temporal.ChronoUnit.DAYS.between(today, d.dueDate());
            boolean major = d.level() != DeviationLevel.MINOR;
            boolean blocking = major && d.batchId() != null;
            boolean dueSoon = daysLeft >= 0 && daysLeft <= 3;
            if (blocking) {
                blockingCnt++;
            }
            if (dueSoon) {
                dueSoonCnt++;
            }
            String riskLevel;
            String riskLabel;
            String action;
            if (d.overdue() || daysLeft < 0) {
                riskLevel = "OVERDUE";
                riskLabel = major ? "重大偏差超期" : "偏差超期";
                action = blocking
                        ? "已超期 " + (-daysLeft) + " 天且阻断关联批次放行，请升级质量负责人立即督办调查与 CAPA"
                        : "偏差调查已超期 " + (-daysLeft) + " 天，请尽快完成根本原因分析并关闭";
                overdueCnt++;
            } else if (blocking) {
                riskLevel = "BLOCKING";
                riskLabel = dueSoon ? "阻断放行·临期" : "阻断批次放行";
                action = d.rootCause() == null
                        ? "重大/严重偏差尚未填写根本原因，关联批次不可放行，请优先调查"
                        : "重大/严重偏差处理中，请确保关联 CAPA 全部验证有效后关闭";
            } else if (dueSoon) {
                riskLevel = "DUE_SOON";
                riskLabel = daysLeft == 0 ? "今日到期" : "3天内到期";
                action = "距偏差调查期限仅 " + daysLeft + " 天，请尽快完成调查与关闭";
            } else {
                riskLevel = "NORMAL";
                riskLabel = "调查进行中";
                action = "请于 " + d.dueDate() + " 前完成调查并签批关闭";
            }
            rows.add(new ClosureRow(d.id(), "DEVIATION", "偏差调查", d.deviationNo(), d.title(),
                    d.levelLabel(), d.areaLabel(), d.batchNo(), d.dueDate(), daysLeft, blocking, dueSoon,
                    riskLevel, riskLabel, action));
        }

        for (CapaView c : openCapas()) {
            long daysLeft = java.time.temporal.ChronoUnit.DAYS.between(today, c.dueDate());
            boolean dueSoon = c.status() == CapaStatus.OPEN && daysLeft >= 0 && daysLeft <= 3;
            String riskLevel;
            String riskLabel;
            String action;
            if (c.status() == CapaStatus.DONE) {
                riskLevel = "VERIFY_PENDING";
                riskLabel = "待 QA 验证";
                action = "负责人 " + c.owner() + " 已登记完成，请非完成人的质量人员尽快验证有效性";
                verifyPendingCnt++;
            } else if (c.overdue() || daysLeft < 0) {
                riskLevel = "OVERDUE";
                riskLabel = "CAPA 已超期";
                action = "CAPA 已超期 " + (-daysLeft) + " 天未完成，请升级督办负责人 " + c.owner() + " 整改";
                overdueCnt++;
            } else if (dueSoon) {
                riskLevel = "DUE_SOON";
                riskLabel = daysLeft == 0 ? "今日到期" : "3天内到期";
                action = "距 CAPA 期限仅 " + daysLeft + " 天，请提醒负责人 " + c.owner() + " 按期闭环";
                dueSoonCnt++;
            } else {
                riskLevel = "NORMAL";
                riskLabel = "整改进行中";
                action = "负责人 " + c.owner() + " 按计划于 " + c.dueDate() + " 前完成";
            }
            rows.add(new ClosureRow(c.id(), "CAPA", "CAPA 整改", c.capaNo(), c.description(),
                    c.actionLabel() + " · " + c.statusLabel(), c.owner(), c.sourceNo(), c.dueDate(), daysLeft, false, dueSoon,
                    riskLevel, riskLabel, action));
        }

        rows.sort(java.util.Comparator.comparingInt((ClosureRow r) -> closureRank(r.riskLevel()))
                .thenComparing(ClosureRow::daysLeft, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                .thenComparing(ClosureRow::refNo));

        return new QmsClosureBoard(rows.size(), overdueCnt, blockingCnt, verifyPendingCnt, dueSoonCnt, rows);
    }

    private static int closureRank(String level) {
        return switch (level) {
            case "OVERDUE" -> 0;
            case "BLOCKING" -> 1;
            case "VERIFY_PENDING" -> 2;
            case "DUE_SOON" -> 3;
            default -> 4;
        };
    }

    @Transactional
    public CapaView createCapa(CapaReq req) {
        String sourceNo = blankToNull(req.sourceNo());
        Long deviationId = req.deviationId();
        if (deviationId != null) {
            Deviation d = require(deviationId);
            requireOpen(d);
            sourceNo = d.getDeviationNo();
        } else if (req.sourceType() == CapaSource.DEVIATION) {
            throw new BizException("来源是偏差时请从偏差记录里添加 CAPA");
        }
        Capa c = new Capa();
        c.setCapaNo(docNo.next("CA", time.today()));
        c.setSourceType(deviationId != null ? CapaSource.DEVIATION : req.sourceType());
        c.setSourceNo(sourceNo);
        c.setDeviationId(deviationId);
        c.setActionType(req.actionType());
        c.setDescription(req.description().trim());
        c.setOwner(req.owner().trim());
        c.setDueDate(req.dueDate());
        c.setStatus(CapaStatus.OPEN);
        c.setCreatedBy(currentUser.usernameOrSystem());
        c.setCreatedAt(time.now());
        capas.save(c);
        audit.log("新建 CAPA", c.getCapaNo(), c.getActionType().getLabel() + "，负责人 " + c.getOwner() + "，期限 " + c.getDueDate()
                + (sourceNo == null ? "" : "，来源 " + sourceNo), c.getDescription());
        return toView(c);
    }

    @Transactional
    public CapaView finishCapa(Long id, CapaDoneReq req) {
        Capa c = requireCapa(id);
        if (c.getStatus() != CapaStatus.OPEN) {
            throw new BizException(c.getCapaNo() + " 当前为" + c.getStatus().getLabel() + "，不能登记完成");
        }
        c.setStatus(CapaStatus.DONE);
        c.setDoneNote(req.note().trim());
        c.setDoneBy(currentUser.usernameOrSystem());
        c.setDoneAt(time.now());
        capas.save(c);
        audit.log("CAPA 完成", c.getCapaNo(), c.getDoneNote());
        return toView(c);
    }

    /** 验证有效性：验证人不能是登记完成的人；验证无效退回继续做。 */
    @Transactional
    public CapaView verifyCapa(Long id, CapaVerifyReq req, AppUser signer) {
        Capa c = requireCapa(id);
        if (c.getStatus() != CapaStatus.DONE) {
            throw new BizException(c.getCapaNo() + " 当前为" + c.getStatus().getLabel() + "，不能验证");
        }
        if (signer.getUsername().equals(c.getDoneBy())) {
            throw new BizException("CAPA 的有效性要由完成人以外的人验证");
        }
        c.setVerifyNote(req.note().trim());
        c.setVerifiedBy(signer.getDisplayName());
        c.setVerifiedAt(time.now());
        c.setStatus(req.effective() ? CapaStatus.VERIFIED : CapaStatus.OPEN);
        capas.save(c);
        audit.log(req.effective() ? "CAPA 验证有效" : "CAPA 验证无效，退回", c.getCapaNo(), "签批人 " + signer.getDisplayName(), c.getVerifyNote());
        return toView(c);
    }

    // ---------- 内部 ----------

    private void requireOpen(Deviation d) {
        if (d.getStatus() != CaseStatus.OPEN) {
            throw new BizException("偏差 " + d.getDeviationNo() + " 已关闭");
        }
    }

    private Deviation require(Long id) {
        return deviations.lockById(id).orElseThrow(() -> BizException.notFound("偏差"));
    }

    private Capa requireCapa(Long id) {
        return capas.lockById(id).orElseThrow(() -> BizException.notFound("CAPA"));
    }

    private DeviationView single(Deviation d) {
        return toViews(List.of(d)).get(0);
    }

    private List<DeviationView> toViews(List<Deviation> list) {
        Map<Long, Batch> bm = batches.findAllById(list.stream().map(Deviation::getBatchId)
                        .filter(java.util.Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(Batch::getId, Function.identity()));
        Map<Long, String> moNos = orders.findAllById(list.stream().map(Deviation::getMoId)
                        .filter(java.util.Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(MfgOrder::getId, MfgOrder::getMoNo));
        LocalDate today = time.today();
        return list.stream().map(d -> {
            Batch b = d.getBatchId() == null ? null : bm.get(d.getBatchId());
            return new DeviationView(d.getId(), d.getDeviationNo(), d.getOccurredDate(), d.getArea(), d.getArea().getLabel(),
                    d.getLevel(), d.getLevel().getLabel(), d.getBatchId(), b == null ? null : b.getBatchNo(),
                    b == null ? null : lookup.item(b.getItemId()).getName(), d.getMoId(), moNos.get(d.getMoId()),
                    d.getTitle(), d.getDescription(), d.getImmediateAction(), d.getRootCause(), d.getDueDate(),
                    d.getStatus() == CaseStatus.OPEN && d.getDueDate().isBefore(today), d.getStatus(), d.getStatus().getLabel(),
                    d.getConclusion(), d.getClosedBy(), d.getClosedAt(), d.getCreatedBy(), d.getCreatedAt());
        }).toList();
    }

    private CapaView toView(Capa c) {
        boolean overdue = c.getStatus() == CapaStatus.OPEN && c.getDueDate().isBefore(time.today());
        return new CapaView(c.getId(), c.getCapaNo(), c.getSourceType(), c.getSourceType().getLabel(), c.getSourceNo(),
                c.getDeviationId(), c.getActionType(), c.getActionType().getLabel(), c.getDescription(), c.getOwner(),
                c.getDueDate(), overdue, c.getStatus(), c.getStatus().getLabel(), c.getDoneNote(), c.getDoneBy(), c.getDoneAt(),
                c.getVerifyNote(), c.getVerifiedBy(), c.getVerifiedAt(), c.getCreatedBy(), c.getCreatedAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
