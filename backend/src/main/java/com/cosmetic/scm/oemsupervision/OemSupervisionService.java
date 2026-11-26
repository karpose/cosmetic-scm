package com.cosmetic.scm.oemsupervision;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.cosmetic.scm.attachment.AttachmentRepository;
import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.CapaSource;
import com.cosmetic.scm.domain.CapaType;
import com.cosmetic.scm.domain.FindingSeverity;
import com.cosmetic.scm.domain.MoStatus;
import com.cosmetic.scm.domain.MoType;
import com.cosmetic.scm.domain.OwnerType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.SupervisionConclusion;
import com.cosmetic.scm.domain.SupervisionMethod;
import com.cosmetic.scm.domain.SupervisionScope;
import com.cosmetic.scm.domain.SupervisionStatus;
import com.cosmetic.scm.domain.rules.SupervisionRule;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.production.MfgOrder;
import com.cosmetic.scm.production.MfgOrderRepository;
import com.cosmetic.scm.qms.Capa;
import com.cosmetic.scm.qms.CapaRepository;
import com.cosmetic.scm.qms.DeviationService;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.CurrentUser;
import com.cosmetic.scm.security.SignatureService;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 受托生产监督（《化妆品生产质量管理规范》第五十三条第一款、第五十七条第二款；检查要点委托生产版第 9、18 项）。
 * 质量、生产岗位登记每次驻厂监督、现场检查、调阅受托方记录或远程视频监督，写明覆盖的环节和监督情况，逐条登记发现的问题：
 * 一般问题写当场纠正的情况或转 CAPA，重大问题必须转 CAPA，都处理了才能提交；质量安全负责人输入密码审核，不能是登记人或提交人。
 * 有在做的委托单、超过 scm.oem.supervision-interval-days 天（默认 90）没有监督的受托厂进总览待办。写操作先锁监督记录（事务第一句）。
 * 规则见 docs/PLM-默认规则.md 的“受托生产监督”。
 */
@Service
@RequiredArgsConstructor
public class OemSupervisionService {

    /** 提交了的和审核过的才算有监督记录（登记中、作废的不算）。 */
    public static final Set<SupervisionStatus> EFFECTIVE = EnumSet.of(SupervisionStatus.SUBMITTED, SupervisionStatus.CONFIRMED);

    /** “委托单”页签列出最近多少天下的或完工的单（还没完成的全部列出）。 */
    public static final int ORDER_LOOKBACK_DAYS = 90;

    private static final List<MoStatus> OPEN_ORDERS = List.of(MoStatus.PLANNED, MoStatus.ISSUED);

    private final OemSupervisionRepository records;
    private final OemSupervisionFindingRepository findings;
    private final MfgOrderRepository orders;
    private final CapaRepository capas;
    private final DeviationService deviations;
    private final AttachmentRepository attachments;
    private final MasterLookup lookup;
    private final SignatureService signature;
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    @Value("${scm.oem.supervision-interval-days:90}")
    private int intervalDays;

    public record SaveReq(@NotNull(message = "请选择受托生产企业") Long factoryId,
                          Long moId,
                          @NotNull(message = "请选择监督方式") SupervisionMethod method,
                          @NotNull(message = "请选择监督日期") LocalDate supervisedOn,
                          @NotEmpty(message = "请至少选一个监督的环节") List<SupervisionScope> scopes,
                          @Size(max = 200, message = "参加人员最多 200 个字") String participants,
                          @NotBlank(message = "请填写监督情况") @Size(max = 1000, message = "监督情况最多 1000 个字") String content) {
    }

    public record FindingReq(@NotNull(message = "请选择问题的程度") FindingSeverity severity,
                             @NotBlank(message = "请写明发现的问题") @Size(max = 500, message = "问题描述最多 500 个字") String description,
                             @Size(max = 500, message = "纠正情况最多 500 个字") String correction) {
    }

    public record CorrectionReq(@NotBlank(message = "请写明当场纠正的情况") @Size(max = 500, message = "纠正情况最多 500 个字") String correction) {
    }

    public record CapaReq(@NotNull(message = "请选择纠正还是预防") CapaType actionType,
                          @NotBlank(message = "请填写措施内容") @Size(max = 900, message = "措施内容最多 900 个字") String description,
                          @NotBlank(message = "请填写负责人") @Size(max = 64) String owner,
                          @NotNull(message = "请选择完成期限") LocalDate dueDate) {
    }

    public record ConfirmReq(@Size(max = 300, message = "审核意见最多 300 个字") String note, @NotBlank(message = "请输入签批密码") String password) {
    }

    public record NoteReq(@NotBlank(message = "请写明原因") @Size(max = 300, message = "原因最多 300 个字") String note) {
    }

    public record Option(String code, String label) {
    }

    /** handled：已当场纠正（一般问题）或已转 CAPA。 */
    public record FindingView(Long id, int seq, FindingSeverity severity, String severityLabel, String description, String correction, Long capaId,
                              String capaNo, String capaStatus, String capaStatusLabel, boolean handled, String createdBy, LocalDateTime createdAt) {
    }

    public record RecordView(Long id, String supervisionNo, Long factoryId, String factoryName, Long moId, String moNo, String fgCode, String fgName,
                             SupervisionMethod method, String methodLabel, LocalDate supervisedOn, List<SupervisionScope> scopes, String scopeLabels,
                             String participants, String content, SupervisionStatus status, String statusLabel, SupervisionConclusion conclusion,
                             String conclusionLabel, List<FindingView> findings, int majorCount, int unhandledCount, int openCapaCount, boolean hasFile,
                             String rejectNote, String reviewNote, String cancelReason, String createdBy, LocalDateTime createdAt, String submittedBy,
                             LocalDateTime submittedAt, String reviewedBy, LocalDateTime reviewedAt) {
    }

    /** 一家受托厂的监督情况。recentCount：最近 intervalDays 天的监督次数；due：有在做的单子、太久没监督。 */
    public record FactoryRow(Long factoryId, String factoryCode, String factoryName, boolean qualified, int openOrders, LocalDate lastSupervised,
                             long recentCount, boolean due) {
    }

    /** 一张委托单有没有针对它的监督。records：登记中、待审核、已审核的监督记录（不含作废）。 */
    public record OrderRow(Long moId, String moNo, Long factoryId, String factoryName, String fgCode, String fgName, MoStatus status, String statusLabel,
                           LocalDateTime createdAt, LocalDateTime completedAt, List<OrderRecord> records, int effectiveCount) {
    }

    public record OrderRecord(Long id, String supervisionNo, String methodLabel, LocalDate supervisedOn, SupervisionStatus status, String statusLabel) {
    }

    public record Overview(List<RecordView> records, List<FactoryRow> factories, List<Option> methods, List<Option> scopes, List<Option> severities,
                           int intervalDays) {
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public Overview overview() {
        return new Overview(views(records.findTop300ByOrderByIdDesc()), factories(),
                Arrays.stream(SupervisionMethod.values()).map(m -> new Option(m.name(), m.getLabel())).toList(),
                Arrays.stream(SupervisionScope.values()).map(m -> new Option(m.name(), m.getLabel())).toList(),
                Arrays.stream(FindingSeverity.values()).map(m -> new Option(m.name(), m.getLabel())).toList(), intervalDays);
    }

    @Transactional(readOnly = true)
    public RecordView detail(Long id) {
        return views(List.of(records.findById(id).orElseThrow(() -> BizException.notFound("监督记录")))).get(0);
    }

    /** 全部监督记录，导出用。 */
    @Transactional(readOnly = true)
    public List<RecordView> listAll() {
        return views(records.findAllByOrderByIdDesc());
    }

    @Transactional(readOnly = true)
    public List<RecordView> pendingReview() {
        return views(records.findByStatusInOrderById(List.of(SupervisionStatus.SUBMITTED)));
    }

    /** 每家受托生产企业：在做的委托单、最近一次监督、最近 intervalDays 天的次数。 */
    @Transactional(readOnly = true)
    public List<FactoryRow> factories() {
        LocalDate today = time.today();
        List<Partner> oems = lookup.partnerMap().values().stream().filter(p -> p.getKind() == PartnerKind.OEM)
                .sorted(Comparator.comparing(Partner::getCode)).toList();
        if (oems.isEmpty()) {
            return List.of();
        }
        Map<Long, Integer> open = new HashMap<>();
        orders.findByMoTypeAndStatusIn(MoType.OEM, OPEN_ORDERS).forEach(m -> {
            if (m.getFactoryId() != null) {
                open.merge(m.getFactoryId(), 1, Integer::sum);
            }
        });
        Map<Long, LocalDate> last = new HashMap<>();
        records.lastDateByFactory(EFFECTIVE).forEach(r -> last.put((Long) r[0], (LocalDate) r[1]));
        Map<Long, Long> recent = new HashMap<>();
        records.countSinceByFactory(EFFECTIVE, today.minusDays(intervalDays)).forEach(r -> recent.put((Long) r[0], ((Number) r[1]).longValue()));
        return oems.stream().map(p -> {
            int n = open.getOrDefault(p.getId(), 0);
            LocalDate l = last.get(p.getId());
            return new FactoryRow(p.getId(), p.getCode(), p.getName(), p.isQualified(), n, l, recent.getOrDefault(p.getId(), 0L),
                    SupervisionRule.due(n, l, today, intervalDays));
        }).toList();
    }

    /** 有在做的委托单、太久没有监督的受托厂（总览待办）。 */
    @Transactional(readOnly = true)
    public List<FactoryRow> dueFactories() {
        return factories().stream().filter(FactoryRow::due).toList();
    }

    public int intervalDays() {
        return intervalDays;
    }

    /** 还没完成的委托单，和最近 90 天下的或完工的委托单（不含已取消），各有哪些针对它的监督。 */
    @Transactional(readOnly = true)
    public List<OrderRow> orders() {
        LocalDateTime since = time.today().minusDays(ORDER_LOOKBACK_DAYS).atStartOfDay();
        LocalDateTime until = time.now().plusDays(1);
        Map<Long, MfgOrder> map = new LinkedHashMap<>();
        orders.findByMoTypeAndStatusIn(MoType.OEM, OPEN_ORDERS).forEach(m -> map.put(m.getId(), m));
        orders.findByMoTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(MoType.OEM, since, until).forEach(m -> map.put(m.getId(), m));
        orders.findByMoTypeAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(MoType.OEM, since, until).forEach(m -> map.put(m.getId(), m));
        List<MfgOrder> list = map.values().stream().filter(m -> m.getStatus() != MoStatus.CANCELLED)
                .sorted(Comparator.comparing(MfgOrder::getId).reversed()).toList();
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, List<OemSupervision>> byMo = records.findByMoIdInAndStatusInOrderBySupervisedOnAscIdAsc(list.stream().map(MfgOrder::getId).toList(),
                EnumSet.of(SupervisionStatus.DRAFT, SupervisionStatus.SUBMITTED, SupervisionStatus.CONFIRMED)).stream()
                .collect(Collectors.groupingBy(OemSupervision::getMoId));
        Map<Long, Item> im = lookup.itemMap();
        Map<Long, Partner> pm = lookup.partnerMap();
        return list.stream().map(m -> {
            List<OemSupervision> rs = byMo.getOrDefault(m.getId(), List.of());
            Item fg = im.get(m.getFgItemId());
            Partner f = pm.get(m.getFactoryId());
            return new OrderRow(m.getId(), m.getMoNo(), m.getFactoryId(), f == null ? null : f.getName(), fg == null ? null : fg.getCode(),
                    fg == null ? null : fg.getName(), m.getStatus(), m.getStatus().getLabel(), m.getCreatedAt(), m.getCompletedAt(),
                    rs.stream().map(r -> new OrderRecord(r.getId(), r.getSupervisionNo(), r.getMethod().getLabel(), r.getSupervisedOn(), r.getStatus(),
                            r.getStatus().getLabel())).toList(),
                    (int) rs.stream().filter(r -> EFFECTIVE.contains(r.getStatus())).count());
        }).toList();
    }

    // ---------- 写操作 ----------

    @Transactional
    public RecordView create(SaveReq req) {
        Partner f = validate(req);
        OemSupervision s = new OemSupervision();
        s.setSupervisionNo(docNo.next("JD", time.today()));
        apply(s, req);
        s.setStatus(SupervisionStatus.DRAFT);
        s.setCreatedBy(currentUser.usernameOrSystem());
        s.setCreatedAt(time.now());
        records.save(s);
        audit.log("登记受托生产监督", s.getSupervisionNo(), describe(s, f), s.getContent());
        return views(List.of(s)).get(0);
    }

    /** 修改登记中（含退回的）的监督记录。先锁监督记录（事务第一句）。 */
    @Transactional
    public RecordView update(Long id, SaveReq req) {
        OemSupervision s = lockDraft(id, "修改");
        String before = describe(s, lookup.partner(s.getFactoryId()));
        String beforeContent = s.getContent();
        Partner f = validate(req);
        apply(s, req);
        records.save(s);
        audit.log("修改受托生产监督", s.getSupervisionNo(), "修改前：" + before + "；修改后：" + describe(s, f),
                Objects.equals(beforeContent, s.getContent()) ? null : "监督情况修改前：" + beforeContent);
        return views(List.of(s)).get(0);
    }

    @Transactional
    public RecordView addFinding(Long id, FindingReq req) {
        OemSupervision s = lockDraft(id, "登记问题");
        OemSupervisionFinding f = new OemSupervisionFinding();
        f.setSupervisionId(s.getId());
        f.setSeq(findings.maxSeq(s.getId()) + 1);
        f.setSeverity(req.severity());
        f.setDescription(req.description().trim());
        f.setCorrection(blankToNull(req.correction()));
        f.setCreatedBy(currentUser.usernameOrSystem());
        f.setCreatedAt(time.now());
        findings.save(f);
        audit.log("登记监督发现的问题", s.getSupervisionNo(), "问题 " + f.getSeq() + "（" + f.getSeverity().getLabel() + "）：" + f.getDescription(),
                f.getCorrection() == null ? null : "当场纠正：" + f.getCorrection());
        return views(List.of(s)).get(0);
    }

    /** 一般问题补写当场纠正的情况。 */
    @Transactional
    public RecordView correct(Long id, Long findingId, CorrectionReq req) {
        OemSupervision s = lockDraft(id, "登记纠正情况");
        OemSupervisionFinding f = lockFinding(s, findingId);
        if (f.getSeverity() != FindingSeverity.MINOR) {
            throw new BizException("问题 " + f.getSeq() + " 是重大问题，要转 CAPA，不能只写当场纠正");
        }
        String before = f.getCorrection();
        f.setCorrection(req.correction().trim());
        findings.save(f);
        audit.log("登记监督问题的纠正情况", s.getSupervisionNo(), "问题 " + f.getSeq() + "：" + f.getCorrection(), before == null ? null : "修改前：" + before);
        return views(List.of(s)).get(0);
    }

    /** 删掉登记错的问题（登记中、还没转 CAPA 的才能删），后面的序号不变。 */
    @Transactional
    public RecordView removeFinding(Long id, Long findingId) {
        OemSupervision s = lockDraft(id, "删除问题");
        OemSupervisionFinding f = lockFinding(s, findingId);
        if (f.getCapaId() != null) {
            throw new BizException("问题 " + f.getSeq() + " 已经转了 CAPA，不能删除");
        }
        findings.delete(f);
        findings.flush();
        audit.log("删除监督发现的问题", s.getSupervisionNo(), "问题 " + f.getSeq() + "（" + f.getSeverity().getLabel() + "）：" + f.getDescription());
        return views(List.of(s)).get(0);
    }

    /** 问题转 CAPA，来源记“受托生产监督”。 */
    @Transactional
    public RecordView createCapa(Long id, Long findingId, CapaReq req) {
        OemSupervision s = lockDraft(id, "转 CAPA");
        OemSupervisionFinding f = lockFinding(s, findingId);
        if (f.getCapaId() != null) {
            throw new BizException("问题 " + f.getSeq() + " 已经转过 CAPA");
        }
        if (req.dueDate().isBefore(time.today())) {
            throw new BizException("完成期限不能早于今天");
        }
        Partner factory = lookup.partner(s.getFactoryId());
        DeviationService.CapaView capa = deviations.createCapa(new DeviationService.CapaReq(CapaSource.SUPERVISION, s.getSupervisionNo() + " 问题" + f.getSeq(),
                null, req.actionType(), "受托生产监督（" + factory.getName() + "）问题 " + f.getSeq() + "：" + req.description().trim(), req.owner(), req.dueDate()));
        f.setCapaId(capa.id());
        findings.save(f);
        audit.log("监督问题转 CAPA", s.getSupervisionNo(), "问题 " + f.getSeq() + "，CAPA " + capa.capaNo());
        return views(List.of(s)).get(0);
    }

    /** 提交审核：发现的问题都处理了（重大问题转了 CAPA，一般问题当场纠正或转了 CAPA）。 */
    @Transactional
    public RecordView submit(Long id) {
        OemSupervision s = lockDraft(id, "提交审核");
        List<OemSupervisionFinding> list = findings.lockBySupervisionId(s.getId());
        List<String> unhandled = SupervisionRule.unhandled(list.stream().map(OemSupervisionService::rule).toList());
        if (!unhandled.isEmpty()) {
            throw new BizException(String.join("；", unhandled));
        }
        if (s.getSupervisedOn().isAfter(time.today())) {
            throw new BizException("监督日期 " + s.getSupervisedOn() + " 还没到，监督做完再提交");
        }
        s.setStatus(SupervisionStatus.SUBMITTED);
        s.setSubmittedBy(currentUser.usernameOrSystem());
        s.setSubmittedAt(time.now());
        s.setRejectNote(null);
        records.save(s);
        SupervisionConclusion c = SupervisionRule.conclusion(list.stream().map(OemSupervisionFinding::getSeverity).toList());
        audit.log("提交受托生产监督", s.getSupervisionNo(), c.getLabel() + (list.isEmpty() ? "" : "，问题 " + list.size() + " 个"));
        return views(List.of(s)).get(0);
    }

    /** 质量安全负责人审核：输入密码，不能是登记人或提交人。 */
    @Transactional
    public RecordView confirm(Long id, ConfirmReq req) {
        OemSupervision s = lock(id);
        require(s, SupervisionStatus.SUBMITTED, "审核");
        AppUser signer = signature.verify(req.password());
        if (signer.getUsername().equals(s.getCreatedBy()) || signer.getUsername().equals(s.getSubmittedBy())) {
            throw new BizException("审核人不能是监督记录的登记人或提交人");
        }
        s.setStatus(SupervisionStatus.CONFIRMED);
        s.setReviewedBy(signer.getUsername());
        s.setReviewedAt(time.now());
        s.setReviewNote(blankToNull(req.note()));
        records.save(s);
        audit.log("审核受托生产监督", s.getSupervisionNo(), lookup.partner(s.getFactoryId()).getName() + "，" + s.getSupervisedOn() + " " + s.getMethod().getLabel(),
                s.getReviewNote());
        return views(List.of(s)).get(0);
    }

    /** 退回：回到登记中，登记人补充后重新提交。转出的 CAPA 保留。 */
    @Transactional
    public RecordView reject(Long id, NoteReq req) {
        OemSupervision s = lock(id);
        require(s, SupervisionStatus.SUBMITTED, "退回");
        s.setStatus(SupervisionStatus.DRAFT);
        s.setRejectNote(req.note().trim());
        records.save(s);
        audit.log("退回受托生产监督", s.getSupervisionNo(), "退回补充", req.note().trim());
        return views(List.of(s)).get(0);
    }

    /** 作废（登记错了）：登记中和待审核的可以作废，问题和转出的 CAPA 都保留。 */
    @Transactional
    public RecordView cancel(Long id, NoteReq req) {
        OemSupervision s = lock(id);
        if (s.getStatus() != SupervisionStatus.DRAFT && s.getStatus() != SupervisionStatus.SUBMITTED) {
            throw new BizException("监督记录 " + s.getSupervisionNo() + " 当前为" + s.getStatus().getLabel() + "，不能作废");
        }
        s.setStatus(SupervisionStatus.CANCELLED);
        s.setCancelReason(req.note().trim());
        records.save(s);
        audit.log("作废受托生产监督", s.getSupervisionNo(), "问题和 CAPA 保留", req.note().trim());
        return views(List.of(s)).get(0);
    }

    // ---------- 内部 ----------

    private Partner validate(SaveReq req) {
        Partner f = lookup.partner(req.factoryId());
        if (f.getKind() != PartnerKind.OEM) {
            throw new BizException(f.getName() + " 不是受托生产企业，自有工厂按批生产记录管理");
        }
        if (req.supervisedOn().isAfter(time.today())) {
            throw new BizException("监督日期不能晚于今天");
        }
        if (req.scopes().stream().anyMatch(Objects::isNull)) {
            throw new BizException("请选择监督的环节");
        }
        if (req.moId() != null) {
            MfgOrder m = orders.findById(req.moId()).orElseThrow(() -> BizException.notFound("委托单"));
            if (m.getMoType() != MoType.OEM || !req.factoryId().equals(m.getFactoryId())) {
                throw new BizException("委托单 " + m.getMoNo() + " 不是委托 " + f.getName() + " 生产的");
            }
            if (m.getStatus() == MoStatus.CANCELLED) {
                throw new BizException("委托单 " + m.getMoNo() + " 已取消");
            }
        }
        return f;
    }

    private static void apply(OemSupervision s, SaveReq req) {
        s.setFactoryId(req.factoryId());
        s.setMoId(req.moId());
        s.setMethod(req.method());
        s.setSupervisedOn(req.supervisedOn());
        // 按枚举顺序存，去重
        s.setScopes(EnumSet.copyOf(req.scopes()).stream().map(Enum::name).collect(Collectors.joining(",")));
        s.setParticipants(blankToNull(req.participants()));
        s.setContent(req.content().trim());
    }

    private String describe(OemSupervision s, Partner f) {
        String mo = s.getMoId() == null ? "" : "，委托单 " + orders.findById(s.getMoId()).map(MfgOrder::getMoNo).orElse(String.valueOf(s.getMoId()));
        return f.getName() + mo + "，" + s.getSupervisedOn() + " " + s.getMethod().getLabel() + "，环节：" + scopeLabels(scopesOf(s))
                + (s.getParticipants() == null ? "" : "，参加人员：" + s.getParticipants());
    }

    static List<SupervisionScope> scopesOf(OemSupervision s) {
        if (s.getScopes() == null || s.getScopes().isBlank()) {
            return List.of();
        }
        return Arrays.stream(s.getScopes().split(",")).map(String::trim).filter(x -> !x.isEmpty()).map(SupervisionScope::valueOf).toList();
    }

    private static String scopeLabels(List<SupervisionScope> list) {
        return list.stream().map(SupervisionScope::getLabel).collect(Collectors.joining("、"));
    }

    private static SupervisionRule.Finding rule(OemSupervisionFinding f) {
        return new SupervisionRule.Finding(f.getSeq(), f.getSeverity(), f.getCorrection() != null && !f.getCorrection().isBlank(), f.getCapaId() != null);
    }

    private OemSupervision lock(Long id) {
        return records.lockById(id).orElseThrow(() -> BizException.notFound("监督记录"));
    }

    private OemSupervision lockDraft(Long id, String action) {
        OemSupervision s = lock(id);
        require(s, SupervisionStatus.DRAFT, action);
        return s;
    }

    private OemSupervisionFinding lockFinding(OemSupervision s, Long findingId) {
        return findings.lockByIdAndSupervisionId(findingId, s.getId()).orElseThrow(() -> BizException.notFound("监督发现的问题"));
    }

    private static void require(OemSupervision s, SupervisionStatus need, String action) {
        if (s.getStatus() != need) {
            throw new BizException("监督记录 " + s.getSupervisionNo() + " 当前为" + s.getStatus().getLabel() + "，不能" + action);
        }
    }

    /** 一次查完问题、CAPA、委托单和附件，不在循环里查库。 */
    private List<RecordView> views(List<OemSupervision> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<Long> ids = list.stream().map(OemSupervision::getId).toList();
        Map<Long, List<OemSupervisionFinding>> byRecord = findings.findBySupervisionIdInOrderBySupervisionIdAscSeqAsc(ids).stream()
                .collect(Collectors.groupingBy(OemSupervisionFinding::getSupervisionId));
        Map<Long, Capa> capaMap = new HashMap<>();
        capas.findAllById(byRecord.values().stream().flatMap(List::stream).map(OemSupervisionFinding::getCapaId).filter(Objects::nonNull).distinct().toList())
                .forEach(c -> capaMap.put(c.getId(), c));
        Map<Long, MfgOrder> moMap = new HashMap<>();
        orders.findAllById(list.stream().map(OemSupervision::getMoId).filter(Objects::nonNull).distinct().toList()).forEach(m -> moMap.put(m.getId(), m));
        Set<Long> withFile = new HashSet<>(attachments.findOwnerIdsWithFiles(OwnerType.OEM_SUPERVISION, ids));
        Map<Long, Item> im = lookup.itemMap();
        Map<Long, Partner> pm = lookup.partnerMap();
        List<RecordView> out = new ArrayList<>();
        for (OemSupervision s : list) {
            List<FindingView> fs = byRecord.getOrDefault(s.getId(), List.of()).stream().map(f -> {
                Capa c = f.getCapaId() == null ? null : capaMap.get(f.getCapaId());
                return new FindingView(f.getId(), f.getSeq(), f.getSeverity(), f.getSeverity().getLabel(), f.getDescription(), f.getCorrection(), f.getCapaId(),
                        c == null ? null : c.getCapaNo(), c == null ? null : c.getStatus().name(), c == null ? null : c.getStatus().getLabel(),
                        SupervisionRule.handled(rule(f)), f.getCreatedBy(), f.getCreatedAt());
            }).toList();
            SupervisionConclusion concl = SupervisionRule.conclusion(fs.stream().map(FindingView::severity).toList());
            MfgOrder mo = s.getMoId() == null ? null : moMap.get(s.getMoId());
            Item fg = mo == null ? null : im.get(mo.getFgItemId());
            Partner f = pm.get(s.getFactoryId());
            List<SupervisionScope> scopes = scopesOf(s);
            out.add(new RecordView(s.getId(), s.getSupervisionNo(), s.getFactoryId(), f == null ? null : f.getName(), s.getMoId(), mo == null ? null : mo.getMoNo(),
                    fg == null ? null : fg.getCode(), fg == null ? null : fg.getName(), s.getMethod(), s.getMethod().getLabel(), s.getSupervisedOn(), scopes,
                    scopeLabels(scopes), s.getParticipants(), s.getContent(), s.getStatus(), s.getStatus().getLabel(), concl, concl.getLabel(), fs,
                    (int) fs.stream().filter(x -> x.severity() == FindingSeverity.MAJOR).count(), (int) fs.stream().filter(x -> !x.handled()).count(),
                    (int) fs.stream().filter(x -> x.capaId() != null && !"VERIFIED".equals(x.capaStatus())).count(), withFile.contains(s.getId()),
                    s.getRejectNote(), s.getReviewNote(), s.getCancelReason(), s.getCreatedBy(), s.getCreatedAt(), s.getSubmittedBy(), s.getSubmittedAt(),
                    s.getReviewedBy(), s.getReviewedAt()));
        }
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
