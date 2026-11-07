package com.cosmetic.scm.plm;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.CaseStatus;
import com.cosmetic.scm.domain.FormulaStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.SourceType;
import com.cosmetic.scm.domain.StabilityPullStatus;
import com.cosmetic.scm.domain.StabilityStudyStatus;
import com.cosmetic.scm.domain.StabilityType;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.production.MfgOrder;
import com.cosmetic.scm.production.MfgOrderRepository;
import com.cosmetic.scm.qms.Deviation;
import com.cosmetic.scm.qms.DeviationRepository;
import com.cosmetic.scm.qms.DeviationService;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.CurrentUser;
import com.cosmetic.scm.security.SignatureService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 稳定性试验：登记方案（一个样品、一个条件、若干时间点和检测项目）→ 按时间点取样检测 → 写结论 → QSO 签批。
 * 结果提交后不能改；不合格自动生成偏差。规则都是默认值，见 docs/PLM-默认规则.md。
 */
@Service
@RequiredArgsConstructor
public class StabilityService {

    /** 取样日期可以比计划早或晚这么多天，再早不接受（等于没放够时间），再晚要写原因。 */
    public static final int WINDOW_DAYS = 7;

    private final StabilityStudyRepository studies;
    private final StabilityTestRepository tests;
    private final StabilityPullRepository pulls;
    private final StabilityResultRepository results;
    private final FormulaVersionRepository versions;
    private final BatchRepository batches;
    private final MfgOrderRepository mfgOrders;
    private final DeviationRepository deviations;
    private final DeviationService deviationService;
    private final MasterLookup lookup;
    private final SignatureService signature;
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record TimepointReq(@Min(value = 0, message = "月数不能为负") @Max(value = 120, message = "月数不能超过 120") int months,
                               @Min(value = 0, message = "天数不能为负") @Max(value = 3650, message = "天数不能超过 3650") int days) {
    }

    public record TestReq(@NotBlank(message = "请填写检测项目") @Size(max = 60) String name, @Size(max = 20) String unit,
                          @Digits(integer = 14, fraction = 4, message = "下限最多 4 位小数") BigDecimal minValue,
                          @Digits(integer = 14, fraction = 4, message = "上限最多 4 位小数") BigDecimal maxValue, @Size(max = 200) String criteria) {
    }

    public record CreateReq(@NotNull(message = "请选择成品") Long fgItemId, @NotNull(message = "请选择试验类型") StabilityType studyType, Long formulaVersionId,
                            @Size(max = 40) String batchNo, @Size(max = 100) String sampleRef, @NotBlank(message = "请填写试验条件") @Size(max = 120) String condition,
                            @NotNull(message = "请选择开始日期") LocalDate startDate,
                            @NotEmpty(message = "请至少设一个时间点") @Size(max = 24, message = "时间点最多 24 个") List<@Valid TimepointReq> timepoints,
                            @NotEmpty(message = "请至少设一个检测项目") @Size(max = 30, message = "检测项目最多 30 个") List<@Valid TestReq> tests, @Size(max = 500) String note) {
    }

    public record ResultReq(@NotNull(message = "请选择检测项目") Long testId, @Digits(integer = 14, fraction = 4, message = "实测值最多 4 位小数") BigDecimal value,
                            @Size(max = 200) String text, Boolean passed) {
    }

    public record RecordReq(@NotNull(message = "请选择取样日期") LocalDate doneDate, @NotEmpty(message = "请填写检测结果") List<@Valid ResultReq> results, @Size(max = 500) String note) {
    }

    public record NoteReq(@NotBlank(message = "请写明原因") @Size(max = 300) String note) {
    }

    public record ConcludeReq(@NotBlank(message = "请填写结论") @Size(max = 1000) String conclusion,
                              @NotNull(message = "请填写支持的保质期") @Min(value = 0, message = "保质期不能为负") @Max(value = 120, message = "保质期不能超过 120 个月") Integer supportedMonths) {
    }

    public record ApproveReq(@NotBlank(message = "请输入签批密码") String password) {
    }

    public record StudyRow(Long id, String studyNo, Long fgItemId, String fgCode, String fgName, Integer versionNo, String batchNo, String sampleRef, StabilityType studyType, String studyTypeLabel,
                           String condition, LocalDate startDate, StabilityStudyStatus status, String statusLabel, int pullsTotal, int pullsOpen, LocalDate nextDue, int failed,
                           Integer supportedMonths, String createdBy, String submittedBy, String signedBy, LocalDateTime signedAt) {
    }

    public record TestView(Long id, int lineNo, String name, String unit, BigDecimal minValue, BigDecimal maxValue, String criteria, boolean judgedBySystem) {
    }

    public record ResultView(Long testId, BigDecimal value, String text, boolean passed) {
    }

    public record PullView(Long id, String label, LocalDate dueDate, StabilityPullStatus status, String statusLabel, LocalDate doneDate, String testedBy, LocalDateTime recordedAt,
                           String skipReason, String note, Boolean passed, Long deviationId, String deviationNo, List<ResultView> results) {
    }

    public record StudyDetail(StudyRow study, String note, String conclusion, String rejectNote, String cancelReason, LocalDateTime submittedAt, List<TestView> tests, List<PullView> pulls) {
    }

    /** state：OK 依据够；SHORT 依据短于标的保质期；RUNNING 进行中；STALE 只有旧配方版本的；NONE 没有。 */
    public record ProductCoverage(Long fgItemId, String fgCode, String fgName, Long versionId, Integer versionNo, Integer shelfMonths, String state, Integer supportedMonths) {
    }

    public record DuePull(Long studyId, String studyNo, String fgName, String fgCode, String label, LocalDate dueDate) {
    }

    @Transactional(readOnly = true)
    public List<StudyRow> list() {
        return rows(studies.findTop300ByOrderByIdDesc());
    }

    @Transactional(readOnly = true)
    public StudyDetail detail(Long id) {
        return detailOf(studies.findById(id).orElseThrow(() -> BizException.notFound("稳定性试验")));
    }

    /** 每个在用成品的保质期有没有依据。只看当前生效配方版本上已签批的结论，取其中最长的。 */
    @Transactional(readOnly = true)
    public List<ProductCoverage> coverage() {
        List<Item> fg = lookup.itemMap().values().stream().filter(i -> i.getItemType() == ItemType.FG && i.isActive())
                .sorted(Comparator.comparing(Item::getCode)).toList();
        if (fg.isEmpty()) {
            return List.of();
        }
        Map<Long, FormulaVersion> effective = versions.findByStatus(FormulaStatus.EFFECTIVE).stream().collect(Collectors.toMap(FormulaVersion::getFgItemId, v -> v, (a, b) -> a));
        Map<Long, List<StabilityStudy>> byItem = studies.findByFgItemIdInAndStatusIn(fg.stream().map(Item::getId).toList(),
                List.of(StabilityStudyStatus.ACTIVE, StabilityStudyStatus.REVIEW, StabilityStudyStatus.APPROVED)).stream().collect(Collectors.groupingBy(StabilityStudy::getFgItemId));
        return fg.stream().filter(i -> effective.containsKey(i.getId())).map(i -> {
            FormulaVersion v = effective.get(i.getId());
            List<StabilityStudy> mine = byItem.getOrDefault(i.getId(), List.of());
            Integer supported = mine.stream().filter(s -> s.getStatus() == StabilityStudyStatus.APPROVED && s.getFormulaVersionId().equals(v.getId()))
                    .map(StabilityStudy::getSupportedMonths).max(Comparator.naturalOrder()).orElse(null);
            return new ProductCoverage(i.getId(), i.getCode(), i.getName(), v.getId(), v.getVersionNo(), i.getShelfMonths(), coverageState(mine, v.getId(), supported, i.getShelfMonths()), supported);
        }).toList();
    }

    private static String coverageState(List<StabilityStudy> mine, Long versionId, Integer supported, Integer shelfMonths) {
        if (supported != null) {
            return shelfMonths != null && supported < shelfMonths ? "SHORT" : "OK";
        }
        if (mine.stream().anyMatch(s -> s.getStatus() != StabilityStudyStatus.APPROVED && s.getFormulaVersionId().equals(versionId))) {
            return "RUNNING";
        }
        return mine.stream().anyMatch(s -> s.getStatus() == StabilityStudyStatus.APPROVED) ? "STALE" : "NONE";
    }

    @Transactional(readOnly = true)
    public List<DuePull> due(LocalDate until) {
        Map<Long, Item> items = lookup.itemMap();
        return pulls.findDue(StabilityPullStatus.PENDING, StabilityStudyStatus.ACTIVE, until).stream().map(r -> {
            StabilityPull p = (StabilityPull) r[0];
            StabilityStudy s = (StabilityStudy) r[1];
            Item i = items.get(s.getFgItemId());
            return new DuePull(s.getId(), s.getStudyNo(), i.getName(), i.getCode(), p.getLabel(), p.getDueDate());
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<StudyRow> waitingConclusion() {
        return rows(studies.findWaitingConclusion(StabilityStudyStatus.ACTIVE, StabilityPullStatus.PENDING));
    }

    @Transactional(readOnly = true)
    public List<StudyRow> waitingSignature() {
        return rows(studies.findByStatusOrderById(StabilityStudyStatus.REVIEW));
    }

    @Transactional
    public StudyDetail create(CreateReq req) {
        Item fg = lookup.item(req.fgItemId());
        if (fg.getItemType() != ItemType.FG) {
            throw new BizException(fg.getName() + " 不是成品");
        }
        if (req.startDate().isAfter(time.today())) {
            throw new BizException("开始日期不能晚于今天，样品放进去那天才算开始");
        }
        Batch batch = req.batchNo() == null || req.batchNo().isBlank() ? null : batches.findByBatchNo(req.batchNo().trim()).orElseThrow(() -> new BizException("批号 " + req.batchNo().trim() + " 不存在"));
        String sampleRef = blankToNull(req.sampleRef());
        if (batch == null && sampleRef == null) {
            throw new BizException("请填写批号，没有批号的（如小试样品）请写明样品来源");
        }
        if (batch != null && !batch.getItemId().equals(fg.getId())) {
            throw new BizException("批号 " + batch.getBatchNo() + " 不是 " + fg.getName() + " 的批次");
        }
        FormulaVersion v = version(req, fg, batch);
        Set<String> seenTimes = new HashSet<>();
        for (TimepointReq t : req.timepoints()) {
            if (!seenTimes.add(t.months() + "/" + t.days())) {
                throw new BizException("时间点“" + labelOf(t.months(), t.days()) + "”重复了");
            }
        }
        Set<String> seenTests = new HashSet<>();
        for (TestReq t : req.tests()) {
            if (!seenTests.add(t.name().trim())) {
                throw new BizException("检测项目“" + t.name().trim() + "”重复了");
            }
            if (t.minValue() != null && t.maxValue() != null && t.minValue().compareTo(t.maxValue()) > 0) {
                throw new BizException("检测项目“" + t.name().trim() + "”的下限大于上限");
            }
        }
        StabilityStudy s = new StabilityStudy();
        s.setStudyNo(docNo.next("ST", time.today()));
        s.setFgItemId(fg.getId());
        s.setFormulaVersionId(v.getId());
        s.setBatchId(batch == null ? null : batch.getId());
        s.setSampleRef(sampleRef);
        s.setStudyType(req.studyType());
        s.setConditionText(req.condition().trim());
        s.setStartDate(req.startDate());
        s.setNote(blankToNull(req.note()));
        s.setStatus(StabilityStudyStatus.ACTIVE);
        s.setCreatedBy(currentUser.usernameOrSystem());
        s.setCreatedAt(time.now());
        studies.save(s);
        int line = 1;
        for (TestReq t : req.tests()) {
            StabilityTest x = new StabilityTest();
            x.setStudyId(s.getId());
            x.setLineNo(line++);
            x.setName(t.name().trim());
            x.setUnit(blankToNull(t.unit()));
            x.setMinValue(t.minValue());
            x.setMaxValue(t.maxValue());
            x.setCriteria(blankToNull(t.criteria()));
            tests.save(x);
        }
        for (TimepointReq t : req.timepoints()) {
            StabilityPull p = new StabilityPull();
            p.setStudyId(s.getId());
            p.setLabel(labelOf(t.months(), t.days()));
            p.setMonths(t.months());
            p.setDays(t.days());
            p.setDueDate(req.startDate().plusMonths(t.months()).plusDays(t.days()));
            p.setStatus(StabilityPullStatus.PENDING);
            pulls.save(p);
        }
        audit.log("登记稳定性试验", s.getStudyNo(), fg.getName() + " V" + v.getVersionNo() + "，" + req.studyType().getLabel() + "，" + s.getConditionText()
                + "，样品 " + (batch != null ? batch.getBatchNo() : sampleRef) + "，" + req.timepoints().size() + " 个时间点");
        return detailOf(s);
    }

    /** 批次是生产单做出来的，配方版本就是它发料时锁定的版本，不能说成别的版本的依据。 */
    private FormulaVersion version(CreateReq req, Item fg, Batch batch) {
        Long fromBatch = batch != null && batch.getSourceType() == SourceType.MO ? mfgOrders.findByMoNo(batch.getSourceNo()).map(MfgOrder::getFormulaVersionId).orElse(null) : null;
        if (fromBatch != null && req.formulaVersionId() != null && !fromBatch.equals(req.formulaVersionId())) {
            throw new BizException("批号 " + batch.getBatchNo() + " 是按另一个配方版本生产的，不能作为这个版本的依据");
        }
        Long wanted = req.formulaVersionId() != null ? req.formulaVersionId() : fromBatch;
        if (wanted != null) {
            return versions.findById(wanted).filter(x -> x.getFgItemId().equals(fg.getId())).orElseThrow(() -> new BizException("配方版本不属于 " + fg.getName()));
        }
        return versions.findFirstByFgItemIdAndStatus(fg.getId(), FormulaStatus.EFFECTIVE).orElseThrow(() -> new BizException(fg.getName() + " 还没有生效配方，请先录入配方"));
    }

    @Transactional
    public StudyDetail record(Long studyId, Long pullId, RecordReq req) {
        StabilityStudy s = lockActive(studyId, "录入结果");
        StabilityPull p = pendingPull(s, pullId);
        if (req.doneDate().isAfter(time.today())) {
            throw new BizException("取样日期不能晚于今天");
        }
        if (req.doneDate().isBefore(p.getDueDate().minusDays(WINDOW_DAYS))) {
            throw new BizException(p.getLabel() + "的计划日期是 " + p.getDueDate() + "，最早 " + p.getDueDate().minusDays(WINDOW_DAYS) + " 才能取样，时间不够的结果不能作为依据");
        }
        String note = blankToNull(req.note());
        if (req.doneDate().isAfter(p.getDueDate().plusDays(WINDOW_DAYS)) && note == null) {
            throw new BizException(p.getLabel() + "的计划日期是 " + p.getDueDate() + "，超过 " + WINDOW_DAYS + " 天了，请在备注里写明原因");
        }
        List<StabilityTest> testList = tests.findByStudyIdOrderByLineNo(s.getId());
        Map<Long, ResultReq> byTest = new HashMap<>();
        for (ResultReq r : req.results()) {
            if (byTest.put(r.testId(), r) != null) {
                throw new BizException("同一个检测项目填了两次");
            }
        }
        Set<Long> known = testList.stream().map(StabilityTest::getId).collect(Collectors.toSet());
        if (!known.containsAll(byTest.keySet())) {
            throw new BizException("检测项目不属于这次试验");
        }
        List<String> failed = new ArrayList<>();
        List<StabilityResult> out = new ArrayList<>();
        for (StabilityTest t : testList) {
            ResultReq r = byTest.get(t.getId());
            if (r == null) {
                throw new BizException("请填写“" + t.getName() + "”的结果");
            }
            boolean pass = judge(t, r);
            StabilityResult x = new StabilityResult();
            x.setPullId(p.getId());
            x.setTestId(t.getId());
            x.setValueNum(r.value());
            x.setValueText(blankToNull(r.text()));
            x.setPassed(pass);
            out.add(x);
            if (!pass) {
                failed.add(t.getName() + " " + shown(r, t) + (limitsOf(t).isEmpty() ? "" : "（限度 " + limitsOf(t) + "）"));
            }
        }
        if (!failed.isEmpty() && note == null) {
            throw new BizException(String.join("、", failed) + " 不合格，请在备注里写明现场情况");
        }
        results.saveAll(out);
        Item fg = lookup.item(s.getFgItemId());
        p.setStatus(StabilityPullStatus.DONE);
        p.setDoneDate(req.doneDate());
        p.setTestedBy(currentUser.usernameOrSystem());
        p.setRecordedAt(time.now());
        p.setNote(note);
        p.setPassed(failed.isEmpty());
        String detail = fg.getName() + "，" + p.getLabel() + "，取样 " + req.doneDate() + (failed.isEmpty() ? "，全部合格" : "，不合格：" + String.join("、", failed));
        if (!failed.isEmpty()) {
            String description = "稳定性试验 " + s.getStudyNo() + "（" + s.getConditionText() + "）" + p.getLabel() + "不合格：" + String.join("、", failed) + "。现场情况：" + note;
            Deviation d = deviationService.createForStudy(s.getStudyNo(), s.getBatchId(), req.doneDate(), fg.getName() + " 稳定性试验 " + p.getLabel() + " 不合格", clip(description, 1000));
            p.setDeviationId(d.getId());
            detail += "，偏差 " + d.getDeviationNo();
        }
        pulls.save(p);
        audit.log("录入稳定性结果", s.getStudyNo(), detail);
        return detailOf(s);
    }

    /** 有上下限的由系统判定，填的合格不合格不算数；没有上下限的人工判定。 */
    private static boolean judge(StabilityTest t, ResultReq r) {
        if (t.getMinValue() != null || t.getMaxValue() != null) {
            if (r.value() == null) {
                throw new BizException("“" + t.getName() + "”有限度（" + limitsOf(t) + "），请填写实测数值");
            }
            return (t.getMinValue() == null || r.value().compareTo(t.getMinValue()) >= 0) && (t.getMaxValue() == null || r.value().compareTo(t.getMaxValue()) <= 0);
        }
        if (r.value() == null && (r.text() == null || r.text().isBlank())) {
            throw new BizException("请填写“" + t.getName() + "”的结果");
        }
        if (r.passed() == null) {
            throw new BizException("请判定“" + t.getName() + "”是否合格");
        }
        return r.passed();
    }

    /** 样品没了、仪器坏了，这个时间点做不了。记下原因，结论时要说明。 */
    @Transactional
    public StudyDetail skip(Long studyId, Long pullId, NoteReq req) {
        StabilityStudy s = lockActive(studyId, "登记未取样");
        StabilityPull p = pendingPull(s, pullId);
        p.setStatus(StabilityPullStatus.SKIPPED);
        p.setSkipReason(req.note().trim());
        p.setTestedBy(currentUser.usernameOrSystem());
        p.setRecordedAt(time.now());
        pulls.save(p);
        audit.log("稳定性未取样", s.getStudyNo(), lookup.item(s.getFgItemId()).getName() + "，" + p.getLabel(), req.note().trim());
        return detailOf(s);
    }

    @Transactional
    public StudyDetail conclude(Long id, ConcludeReq req) {
        StabilityStudy s = lockActive(id, "提交结论");
        List<StabilityPull> list = pulls.findByStudyIdOrderByDueDateAscIdAsc(id);
        List<String> open = list.stream().filter(p -> p.getStatus() == StabilityPullStatus.PENDING).map(StabilityPull::getLabel).toList();
        if (!open.isEmpty()) {
            throw new BizException("还有时间点没完成：" + String.join("、", open) + "。做不了的请登记未取样，不做了的请取消试验");
        }
        if (list.stream().noneMatch(p -> p.getStatus() == StabilityPullStatus.DONE)) {
            throw new BizException("没有任何检测结果，不能下结论，请取消试验");
        }
        s.setStatus(StabilityStudyStatus.REVIEW);
        s.setConclusion(req.conclusion().trim());
        s.setSupportedMonths(req.supportedMonths());
        s.setSubmittedBy(currentUser.usernameOrSystem());
        s.setSubmittedAt(time.now());
        s.setRejectNote(null);
        studies.save(s);
        audit.log("提交稳定性结论", s.getStudyNo(), lookup.item(s.getFgItemId()).getName() + "，支持保质期 " + req.supportedMonths() + " 个月：" + s.getConclusion());
        return detailOf(s);
    }

    @Transactional
    public StudyDetail approve(Long id, ApproveReq req) {
        StabilityStudy s = lock(id);
        requireStatus(s, StabilityStudyStatus.REVIEW, "签批");
        AppUser signer = signature.verify(req.password());
        if (signer.getUsername().equals(s.getSubmittedBy())) {
            throw new BizException("签批人不能是提交结论的人");
        }
        List<Long> devIds = pulls.findByStudyIdOrderByDueDateAscIdAsc(id).stream().map(StabilityPull::getDeviationId).filter(java.util.Objects::nonNull).toList();
        List<String> open = deviations.findAllById(devIds).stream().filter(d -> d.getStatus() != CaseStatus.CLOSED).map(Deviation::getDeviationNo).toList();
        if (!open.isEmpty()) {
            throw new BizException("偏差 " + String.join("、", open) + " 还没关闭，不能签批结论");
        }
        s.setStatus(StabilityStudyStatus.APPROVED);
        s.setSignedBy(signer.getUsername());
        s.setSignedAt(time.now());
        studies.save(s);
        audit.log("签批稳定性结论", s.getStudyNo(), lookup.item(s.getFgItemId()).getName() + "，支持保质期 " + s.getSupportedMonths() + " 个月");
        return detailOf(s);
    }

    @Transactional
    public StudyDetail reject(Long id, NoteReq req) {
        StabilityStudy s = lock(id);
        requireStatus(s, StabilityStudyStatus.REVIEW, "退回");
        s.setStatus(StabilityStudyStatus.ACTIVE);
        s.setRejectNote(req.note().trim());
        studies.save(s);
        audit.log("退回稳定性结论", s.getStudyNo(), lookup.item(s.getFgItemId()).getName(), req.note().trim());
        return detailOf(s);
    }

    @Transactional
    public StudyDetail cancel(Long id, NoteReq req) {
        StabilityStudy s = lockActive(id, "取消");
        s.setStatus(StabilityStudyStatus.CANCELLED);
        s.setCancelReason(req.note().trim());
        studies.save(s);
        audit.log("取消稳定性试验", s.getStudyNo(), lookup.item(s.getFgItemId()).getName(), req.note().trim());
        return detailOf(s);
    }

    /** 已签批的结论出了问题时用，保质期依据随之失效。记录留着。 */
    @Transactional
    public StudyDetail voidStudy(Long id, NoteReq req) {
        StabilityStudy s = lock(id);
        requireStatus(s, StabilityStudyStatus.APPROVED, "作废");
        s.setStatus(StabilityStudyStatus.VOID);
        s.setCancelReason(req.note().trim());
        studies.save(s);
        audit.log("作废稳定性结论", s.getStudyNo(), lookup.item(s.getFgItemId()).getName(), req.note().trim());
        return detailOf(s);
    }

    private StabilityStudy lock(Long id) {
        return studies.lockById(id).orElseThrow(() -> BizException.notFound("稳定性试验"));
    }

    private StabilityStudy lockActive(Long id, String action) {
        StabilityStudy s = lock(id);
        requireStatus(s, StabilityStudyStatus.ACTIVE, action);
        return s;
    }

    private static void requireStatus(StabilityStudy s, StabilityStudyStatus need, String action) {
        if (s.getStatus() != need) {
            throw new BizException("试验 " + s.getStudyNo() + " 当前为" + s.getStatus().getLabel() + "，不能" + action);
        }
    }

    private StabilityPull pendingPull(StabilityStudy s, Long pullId) {
        StabilityPull p = pulls.findById(pullId).filter(x -> x.getStudyId().equals(s.getId())).orElseThrow(() -> BizException.notFound("取样时间点"));
        if (p.getStatus() != StabilityPullStatus.PENDING) {
            throw new BizException(p.getLabel() + "当前为" + p.getStatus().getLabel() + "，结果提交后不能改；录错了请在偏差里说明");
        }
        return p;
    }

    static String labelOf(int months, int days) {
        if (months == 0 && days == 0) {
            return "起始";
        }
        return (months > 0 ? months + " 个月" : "") + (months > 0 && days > 0 ? " " : "") + (days > 0 ? days + " 天" : "");
    }

    private static String limitsOf(StabilityTest t) {
        String unit = t.getUnit() == null ? "" : " " + t.getUnit();
        if (t.getMinValue() != null && t.getMaxValue() != null) {
            return plain(t.getMinValue()) + " ~ " + plain(t.getMaxValue()) + unit;
        }
        if (t.getMinValue() != null) {
            return "≥ " + plain(t.getMinValue()) + unit;
        }
        return t.getMaxValue() == null ? "" : "≤ " + plain(t.getMaxValue()) + unit;
    }

    private static String shown(ResultReq r, StabilityTest t) {
        String v = r.value() != null ? plain(r.value()) + (t.getUnit() == null ? "" : " " + t.getUnit()) : "";
        return r.text() == null || r.text().isBlank() ? v : (v.isEmpty() ? r.text().trim() : v + " " + r.text().trim());
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private List<StudyRow> rows(List<StabilityStudy> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, List<StabilityPull>> byStudy = pulls.findByStudyIdIn(list.stream().map(StabilityStudy::getId).toList()).stream().collect(Collectors.groupingBy(StabilityPull::getStudyId));
        Map<Long, Item> items = lookup.itemMap();
        Map<Long, Integer> versionNos = new HashMap<>();
        versions.findAllById(list.stream().map(StabilityStudy::getFormulaVersionId).distinct().toList()).forEach(v -> versionNos.put(v.getId(), v.getVersionNo()));
        Map<Long, String> batchNos = new HashMap<>();
        batches.findAllById(list.stream().map(StabilityStudy::getBatchId).filter(java.util.Objects::nonNull).distinct().toList()).forEach(b -> batchNos.put(b.getId(), b.getBatchNo()));
        return list.stream().map(s -> {
            List<StabilityPull> mine = byStudy.getOrDefault(s.getId(), List.of());
            List<StabilityPull> open = mine.stream().filter(p -> p.getStatus() == StabilityPullStatus.PENDING).toList();
            Item i = items.get(s.getFgItemId());
            return new StudyRow(s.getId(), s.getStudyNo(), s.getFgItemId(), i.getCode(), i.getName(), versionNos.get(s.getFormulaVersionId()), batchNos.get(s.getBatchId()), s.getSampleRef(), s.getStudyType(), s.getStudyType().getLabel(),
                    s.getConditionText(), s.getStartDate(), s.getStatus(), s.getStatus().getLabel(), mine.size(), open.size(), open.stream().map(StabilityPull::getDueDate).min(Comparator.naturalOrder()).orElse(null),
                    (int) mine.stream().filter(p -> Boolean.FALSE.equals(p.getPassed())).count(), s.getSupportedMonths(), s.getCreatedBy(), s.getSubmittedBy(), s.getSignedBy(), s.getSignedAt());
        }).toList();
    }

    private StudyDetail detailOf(StabilityStudy s) {
        List<StabilityPull> list = pulls.findByStudyIdOrderByDueDateAscIdAsc(s.getId());
        Map<Long, List<StabilityResult>> byPull = results.findByPullIdIn(list.stream().map(StabilityPull::getId).toList()).stream().collect(Collectors.groupingBy(StabilityResult::getPullId));
        Map<Long, String> devNos = new HashMap<>();
        deviations.findAllById(list.stream().map(StabilityPull::getDeviationId).filter(java.util.Objects::nonNull).toList()).forEach(d -> devNos.put(d.getId(), d.getDeviationNo()));
        List<PullView> pullViews = list.stream().map(p -> new PullView(p.getId(), p.getLabel(), p.getDueDate(), p.getStatus(), p.getStatus().getLabel(), p.getDoneDate(), p.getTestedBy(), p.getRecordedAt(), p.getSkipReason(), p.getNote(),
                p.getPassed(), p.getDeviationId(), devNos.get(p.getDeviationId()), byPull.getOrDefault(p.getId(), List.of()).stream().map(r -> new ResultView(r.getTestId(), r.getValueNum(), r.getValueText(), r.isPassed())).toList())).toList();
        List<TestView> testViews = tests.findByStudyIdOrderByLineNo(s.getId()).stream().map(t -> new TestView(t.getId(), t.getLineNo(), t.getName(), t.getUnit(), t.getMinValue(), t.getMaxValue(), t.getCriteria(),
                t.getMinValue() != null || t.getMaxValue() != null)).toList();
        return new StudyDetail(rows(List.of(s)).get(0), s.getNote(), s.getConclusion(), s.getRejectNote(), s.getCancelReason(), s.getSubmittedAt(), testViews, pullViews);
    }
}
