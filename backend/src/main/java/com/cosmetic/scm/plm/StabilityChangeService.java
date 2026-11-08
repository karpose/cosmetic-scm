package com.cosmetic.scm.plm;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.CorrectionStatus;
import com.cosmetic.scm.domain.StabilityPullStatus;
import com.cosmetic.scm.domain.StabilityStudyStatus;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.plm.StabilityService.ResultReq;
import com.cosmetic.scm.plm.StabilityService.StudyDetail;
import com.cosmetic.scm.plm.StabilityService.TestReq;
import com.cosmetic.scm.plm.StabilityService.TimepointReq;
import com.cosmetic.scm.qms.Deviation;
import com.cosmetic.scm.qms.DeviationService;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.CurrentUser;
import com.cosmetic.scm.security.SignatureService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 稳定性试验方案修改和结果更正。方案只能在试验进行中修改：加时间点、取消还没做的时间点、加检测项目，每次记一版、写明原因、本人签名；
 * 已有项目的限度不能改。提交后的结果不能直接改：质量人员申请更正，另一位质量安全负责人批准后才生效，原值保留，按新值重新判定，
 * 改成不合格的自动生成偏差。规则见 docs/PLM-默认规则.md。
 */
@Service
@RequiredArgsConstructor
public class StabilityChangeService {

    private final StabilityStudyRepository studies;
    private final StabilityTestRepository tests;
    private final StabilityPullRepository pulls;
    private final StabilityResultRepository results;
    private final StabilityAmendmentRepository amendments;
    private final StabilityCorrectionRepository corrections;
    private final StabilityService stability;
    private final DeviationService deviationService;
    private final MasterLookup lookup;
    private final SignatureService signature;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record AmendReq(@NotBlank(message = "请写明修改原因") @Size(max = 300) String reason, List<@Valid TimepointReq> addTimepoints, List<Long> cancelPullIds,
                           List<@Valid TestReq> addTests, @NotBlank(message = "请输入签批密码") String password) {
    }

    public record CorrectReq(@NotNull(message = "请选择检测项目") Long testId, @Digits(integer = 14, fraction = 4, message = "实测值最多 4 位小数") BigDecimal value,
                             @Size(max = 200) String text, Boolean passed, @NotBlank(message = "请写明更正原因") @Size(max = 300) String reason) {
    }

    public record DecideReq(@NotBlank(message = "请输入签批密码") String password) {
    }

    public record NoteReq(@NotBlank(message = "请写明原因") @Size(max = 300) String note) {
    }

    /** 待审批的更正，总览提醒用。 */
    public record PendingCorrection(Long studyId, String studyNo, String fgName, String pullLabel, String testName, String requestedBy, LocalDateTime requestedAt) {
    }

    @Transactional(readOnly = true)
    public List<PendingCorrection> pending() {
        List<StabilityCorrection> list = corrections.findByStatusOrderById(CorrectionStatus.PENDING);
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, StabilityStudy> st = studies.findAllById(list.stream().map(StabilityCorrection::getStudyId).distinct().toList()).stream()
                .collect(Collectors.toMap(StabilityStudy::getId, x -> x));
        Map<Long, String> pl = pulls.findAllById(list.stream().map(StabilityCorrection::getPullId).distinct().toList()).stream()
                .collect(Collectors.toMap(StabilityPull::getId, StabilityPull::getLabel));
        Map<Long, String> tn = tests.findAllById(list.stream().map(StabilityCorrection::getTestId).distinct().toList()).stream()
                .collect(Collectors.toMap(StabilityTest::getId, StabilityTest::getName));
        Map<Long, Item> items = lookup.itemMap();
        return list.stream().map(c -> {
            StabilityStudy s = st.get(c.getStudyId());
            return new PendingCorrection(s.getId(), s.getStudyNo(), items.get(s.getFgItemId()).getName(), pl.get(c.getPullId()), tn.get(c.getTestId()), c.getRequestedBy(), c.getRequestedAt());
        }).toList();
    }

    @Transactional
    public StudyDetail amend(Long studyId, AmendReq req) {
        StabilityStudy s = lockActive(studyId, "修改方案");
        AppUser signer = signature.verify(req.password());
        List<TimepointReq> addTimes = req.addTimepoints() == null ? List.of() : req.addTimepoints();
        Set<Long> cancelIds = req.cancelPullIds() == null ? Set.of() : new HashSet<>(req.cancelPullIds());
        List<TestReq> addTests = req.addTests() == null ? List.of() : req.addTests();
        if (addTimes.isEmpty() && cancelIds.isEmpty() && addTests.isEmpty()) {
            throw new BizException("没有要修改的内容");
        }
        List<StabilityPull> all = pulls.findByStudyIdOrderByDueDateAscIdAsc(s.getId());
        Map<Long, StabilityPull> byId = all.stream().collect(Collectors.toMap(StabilityPull::getId, p -> p));
        List<StabilityPull> toCancel = new ArrayList<>();
        for (Long id : cancelIds) {
            StabilityPull p = byId.get(id);
            if (p == null) {
                throw BizException.notFound("取样时间点");
            }
            if (p.getStatus() != StabilityPullStatus.PENDING) {
                throw new BizException(p.getLabel() + "当前为" + p.getStatus().getLabel() + "，只有还没做的时间点能取消");
            }
            toCancel.add(p);
        }
        // 还在方案里的时间点（取消的不算），新加的不能和它们重复
        Set<String> times = all.stream().filter(p -> p.getStatus() != StabilityPullStatus.CANCELLED && !cancelIds.contains(p.getId()))
                .map(p -> p.getMonths() + "/" + p.getDays()).collect(Collectors.toCollection(HashSet::new));
        for (TimepointReq t : addTimes) {
            if (!times.add(t.months() + "/" + t.days())) {
                throw new BizException("时间点“" + StabilityService.labelOf(t.months(), t.days()) + "”已经在方案里了");
            }
        }
        if (times.isEmpty()) {
            throw new BizException("方案里至少要留一个时间点；整个试验不做了请取消试验");
        }
        if (times.size() > 24) {
            throw new BizException("时间点最多 24 个");
        }
        List<StabilityTest> testList = tests.findByStudyIdOrderByLineNo(s.getId());
        Set<String> names = testList.stream().map(StabilityTest::getName).collect(Collectors.toCollection(HashSet::new));
        for (TestReq t : addTests) {
            if (!names.add(t.name().trim())) {
                throw new BizException("检测项目“" + t.name().trim() + "”已经有了；要改限度请加一个新名称的项目");
            }
            if (t.minValue() != null && t.maxValue() != null && t.minValue().compareTo(t.maxValue()) > 0) {
                throw new BizException("检测项目“" + t.name().trim() + "”的下限大于上限");
            }
        }
        if (names.size() > 30) {
            throw new BizException("检测项目最多 30 个");
        }
        int rev = amendments.maxRev(s.getId()) + 1;
        String reason = req.reason().trim();
        List<String> summary = new ArrayList<>();
        if (!addTimes.isEmpty()) {
            for (TimepointReq t : addTimes) {
                StabilityPull p = new StabilityPull();
                p.setStudyId(s.getId());
                p.setLabel(StabilityService.labelOf(t.months(), t.days()));
                p.setMonths(t.months());
                p.setDays(t.days());
                p.setDueDate(s.getStartDate().plusMonths(t.months()).plusDays(t.days()));
                p.setStatus(StabilityPullStatus.PENDING);
                pulls.save(p);
            }
            summary.add("加时间点：" + addTimes.stream().map(t -> StabilityService.labelOf(t.months(), t.days())).collect(Collectors.joining("、")));
        }
        if (!toCancel.isEmpty()) {
            for (StabilityPull p : toCancel) {
                p.setStatus(StabilityPullStatus.CANCELLED);
                p.setSkipReason("方案第 " + rev + " 版取消：" + reason);
                p.setTestedBy(signer.getUsername());
                p.setRecordedAt(time.now());
                pulls.save(p);
            }
            summary.add("取消时间点：" + toCancel.stream().map(StabilityPull::getLabel).collect(Collectors.joining("、")));
        }
        if (!addTests.isEmpty()) {
            int line = testList.stream().mapToInt(StabilityTest::getLineNo).max().orElse(0) + 1;
            for (TestReq t : addTests) {
                StabilityTest x = new StabilityTest();
                x.setStudyId(s.getId());
                x.setLineNo(line++);
                x.setName(t.name().trim());
                x.setUnit(StabilityService.blankToNull(t.unit()));
                x.setMinValue(t.minValue());
                x.setMaxValue(t.maxValue());
                x.setCriteria(StabilityService.blankToNull(t.criteria()));
                tests.save(x);
                String lim = StabilityService.limitsOf(x);
                summary.add("加检测项目：" + x.getName() + (lim.isEmpty() ? (x.getCriteria() == null ? "" : "（" + x.getCriteria() + "）") : "（" + lim + "）"));
            }
        }
        StabilityAmendment a = new StabilityAmendment();
        a.setStudyId(s.getId());
        a.setRevNo(rev);
        a.setReason(reason);
        a.setSummary(clip(String.join("；", summary), 1000));
        a.setCreatedBy(signer.getUsername());
        a.setCreatedAt(time.now());
        amendments.save(a);
        audit.log("修改稳定性方案", s.getStudyNo(), lookup.item(s.getFgItemId()).getName() + "，第 " + rev + " 版：" + a.getSummary(), reason);
        return stability.detailOf(s);
    }

    @Transactional
    public StudyDetail requestCorrection(Long studyId, Long pullId, CorrectReq req) {
        StabilityStudy s = studies.lockById(studyId).orElseThrow(() -> BizException.notFound("稳定性试验"));
        if (s.getStatus() == StabilityStudyStatus.REVIEW) {
            throw new BizException("结论正在等待签批，要更正结果请先请质量安全负责人退回结论");
        }
        if (s.getStatus() != StabilityStudyStatus.ACTIVE) {
            throw new BizException("试验 " + s.getStudyNo() + " 当前为" + s.getStatus().getLabel() + "，不能更正结果");
        }
        StabilityPull p = pulls.findById(pullId).filter(x -> x.getStudyId().equals(s.getId())).orElseThrow(() -> BizException.notFound("取样时间点"));
        if (p.getStatus() != StabilityPullStatus.DONE) {
            throw new BizException(p.getLabel() + "当前为" + p.getStatus().getLabel() + "，没有结果可以更正");
        }
        StabilityTest t = tests.findById(req.testId()).filter(x -> x.getStudyId().equals(s.getId())).orElseThrow(() -> new BizException("检测项目不属于这次试验"));
        StabilityResult r = results.findByPullIdAndTestId(p.getId(), t.getId())
                .orElseThrow(() -> new BizException(p.getLabel() + "没有检测“" + t.getName() + "”（项目是后来加的），没有结果可以更正"));
        if (corrections.existsByPullIdAndTestIdAndStatus(p.getId(), t.getId(), CorrectionStatus.PENDING)) {
            throw new BizException(p.getLabel() + "的“" + t.getName() + "”已有一个更正待审批");
        }
        ResultReq asReq = new ResultReq(t.getId(), req.value(), req.text(), req.passed());
        boolean pass = StabilityService.judge(t, asReq);
        String newText = StabilityService.blankToNull(req.text());
        if (sameValue(r.getValueNum(), req.value()) && Objects.equals(r.getValueText(), newText) && r.isPassed() == pass) {
            throw new BizException("和原来的结果一样，不用更正");
        }
        StabilityCorrection c = new StabilityCorrection();
        c.setStudyId(s.getId());
        c.setPullId(p.getId());
        c.setTestId(t.getId());
        c.setOldValue(r.getValueNum());
        c.setOldText(r.getValueText());
        c.setOldPassed(r.isPassed());
        c.setNewValue(req.value());
        c.setNewText(newText);
        c.setNewPassed(pass);
        c.setReason(req.reason().trim());
        c.setStatus(CorrectionStatus.PENDING);
        c.setRequestedBy(currentUser.usernameOrSystem());
        c.setRequestedAt(time.now());
        corrections.save(c);
        audit.log("申请更正稳定性结果", s.getStudyNo(), p.getLabel() + " " + t.getName() + "：" + shown(r.getValueNum(), r.getValueText(), r.isPassed(), t)
                + " 改为 " + shown(req.value(), newText, pass, t), c.getReason());
        return stability.detailOf(s);
    }

    @Transactional
    public StudyDetail approveCorrection(Long correctionId, DecideReq req) {
        StabilityCorrection c = lockCorrection(correctionId);
        StabilityStudy s = studies.findById(c.getStudyId()).orElseThrow();
        requirePending(c);
        if (s.getStatus() != StabilityStudyStatus.ACTIVE) {
            throw new BizException("试验 " + s.getStudyNo() + " 当前为" + s.getStatus().getLabel() + "，不能批准更正");
        }
        AppUser signer = signature.verify(req.password());
        if (signer.getUsername().equals(c.getRequestedBy())) {
            throw new BizException("批准人不能是申请人");
        }
        StabilityPull p = pulls.findById(c.getPullId()).orElseThrow();
        StabilityTest t = tests.findById(c.getTestId()).orElseThrow();
        StabilityResult r = results.findByPullIdAndTestId(p.getId(), t.getId()).orElseThrow();
        r.setValueNum(c.getNewValue());
        r.setValueText(c.getNewText());
        r.setPassed(c.isNewPassed());
        results.save(r);
        boolean allPass = results.findByPullId(p.getId()).stream().allMatch(StabilityResult::isPassed);
        p.setPassed(allPass);
        String detail = p.getLabel() + " " + t.getName() + "：" + shown(c.getOldValue(), c.getOldText(), c.isOldPassed(), t) + " 改为 " + shown(c.getNewValue(), c.getNewText(), c.isNewPassed(), t);
        // 改成不合格、这个时间点还没有偏差的，和录入时一样生成偏差；原来不合格改成合格的，偏差要另行关闭
        if (!c.isNewPassed() && p.getDeviationId() == null) {
            String fg = lookup.item(s.getFgItemId()).getName();
            String description = "稳定性试验 " + s.getStudyNo() + "（" + s.getConditionText() + "）结果更正后" + p.getLabel() + "不合格：" + t.getName() + " "
                    + shown(c.getNewValue(), c.getNewText(), false, t) + "。更正原因：" + c.getReason();
            Deviation d = deviationService.createForStudy(s.getStudyNo(), s.getBatchId(), p.getDoneDate(), fg + " 稳定性试验 " + p.getLabel() + " 不合格", clip(description, 1000));
            p.setDeviationId(d.getId());
            detail += "，偏差 " + d.getDeviationNo();
        }
        pulls.save(p);
        c.setStatus(CorrectionStatus.APPROVED);
        c.setDecidedBy(signer.getUsername());
        c.setDecidedAt(time.now());
        corrections.save(c);
        audit.log("批准稳定性结果更正", s.getStudyNo(), detail, c.getReason());
        return stability.detailOf(s);
    }

    @Transactional
    public StudyDetail rejectCorrection(Long correctionId, NoteReq req) {
        StabilityCorrection c = lockCorrection(correctionId);
        StabilityStudy s = studies.findById(c.getStudyId()).orElseThrow();
        requirePending(c);
        c.setStatus(CorrectionStatus.REJECTED);
        c.setDecidedBy(currentUser.usernameOrSystem());
        c.setDecidedAt(time.now());
        c.setDecideNote(req.note().trim());
        corrections.save(c);
        StabilityTest t = tests.findById(c.getTestId()).orElseThrow();
        StabilityPull p = pulls.findById(c.getPullId()).orElseThrow();
        audit.log("驳回稳定性结果更正", s.getStudyNo(), p.getLabel() + " " + t.getName(), req.note().trim());
        return stability.detailOf(s);
    }

    /**
     * 先锁试验（和录入、写结论、取消串行），再锁更正单。试验 id 只能查那一列：
     * 先把更正单读进来，加锁读取拿到的就是缓存里的旧状态。
     */
    private StabilityCorrection lockCorrection(Long id) {
        Long studyId = corrections.findStudyIdById(id).orElseThrow(() -> BizException.notFound("结果更正"));
        studies.lockById(studyId).orElseThrow();
        return corrections.lockById(id).orElseThrow(() -> BizException.notFound("结果更正"));
    }

    private static void requirePending(StabilityCorrection c) {
        if (c.getStatus() != CorrectionStatus.PENDING) {
            throw new BizException("这个更正当前为" + c.getStatus().getLabel() + "，不能再处理");
        }
    }

    private StabilityStudy lockActive(Long id, String action) {
        StabilityStudy s = studies.lockById(id).orElseThrow(() -> BizException.notFound("稳定性试验"));
        if (s.getStatus() != StabilityStudyStatus.ACTIVE) {
            throw new BizException("试验 " + s.getStudyNo() + " 当前为" + s.getStatus().getLabel() + "，不能" + action);
        }
        return s;
    }

    private static boolean sameValue(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static String shown(BigDecimal v, String text, boolean passed, StabilityTest t) {
        String s = (v == null ? "" : StabilityService.plain(v) + (t.getUnit() == null ? "" : " " + t.getUnit())) + (text == null ? "" : (v == null ? "" : " ") + text);
        return (s.isBlank() ? "" : s.trim()) + (passed ? "（合格）" : "（不合格）");
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
