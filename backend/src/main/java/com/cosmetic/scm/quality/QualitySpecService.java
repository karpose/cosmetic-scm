package com.cosmetic.scm.quality;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.InspectionStatus;
import com.cosmetic.scm.domain.SpecStatus;
import com.cosmetic.scm.domain.rules.SpecLimit;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.ItemRepository;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.qms.Deviation;
import com.cosmetic.scm.qms.DeviationRepository;
import com.cosmetic.scm.qms.DeviationService;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.CurrentUser;
import com.cosmetic.scm.security.SignatureService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 质量标准和批次检验。标准按物料建立，每次修改是一个新版本，QSO 签批后生效，旧版本自动替代。
 * 有生效标准的物料，放行前最近一次有效检验必须合格；没有标准的照旧按检验报告编号放行。
 * 规则都是默认值，见 docs/PLM-默认规则.md。
 */
@Service
@RequiredArgsConstructor
public class QualitySpecService {

    private final QualitySpecRepository specs;
    private final QualitySpecLineRepository lines;
    private final BatchInspectionRepository inspections;
    private final BatchInspectionResultRepository results;
    private final BatchRepository batches;
    private final ItemRepository items;
    private final DeviationRepository deviations;
    private final DeviationService deviationService;
    private final MasterLookup lookup;
    private final SignatureService signature;
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record LineReq(@NotBlank(message = "请填写检测项目") @Size(max = 60) String name, @Size(max = 100) String method, @Size(max = 20) String unit,
                          @Digits(integer = 14, fraction = 4, message = "下限最多 4 位小数") BigDecimal minValue,
                          @Digits(integer = 14, fraction = 4, message = "上限最多 4 位小数") BigDecimal maxValue, @Size(max = 200) String criteria) {
    }

    public record CreateReq(@NotNull(message = "请选择物料") Long itemId, @NotBlank(message = "请写明制定或修改的原因") @Size(max = 300) String changeNote,
                            @NotEmpty(message = "请至少设一个检测项目") @Size(max = 50, message = "检测项目最多 50 个") List<@Valid LineReq> lines) {
    }

    public record ApproveReq(@NotBlank(message = "请输入签批密码") String password) {
    }

    public record NoteReq(@NotBlank(message = "请写明原因") @Size(max = 300) String note) {
    }

    public record ResultReq(@NotNull(message = "请选择检测项目") Long lineId, @Digits(integer = 14, fraction = 4, message = "实测值最多 4 位小数") BigDecimal value,
                            @Size(max = 200) String text, Boolean passed) {
    }

    public record InspectReq(@NotNull(message = "请选择检验日期") LocalDate inspectedDate, @NotEmpty(message = "请填写检验结果") List<@Valid ResultReq> results, @Size(max = 500) String note) {
    }

    public record LineView(Long id, int lineNo, String name, String method, String unit, BigDecimal minValue, BigDecimal maxValue, String criteria, String limitText, boolean judgedBySystem) {
    }

    public record SpecView(Long id, String specNo, Long itemId, String itemCode, String itemName, String itemTypeLabel, int versionNo, SpecStatus status, String statusLabel,
                           String changeNote, String rejectNote, String createdBy, LocalDateTime createdAt, String approvedBy, LocalDateTime approvedAt, List<LineView> lines) {
    }

    public record ResultView(Long lineId, BigDecimal value, String text, boolean passed) {
    }

    public record InspectionView(Long id, String inspectionNo, Long specId, String specNo, int specVersion, LocalDate inspectedDate, String inspector, String note, boolean passed,
                                 InspectionStatus status, String statusLabel, String voidReason, String voidedBy, Long deviationId, String deviationNo, LocalDateTime createdAt, List<ResultView> results) {
    }

    /** 放行弹窗用：生效标准、这个批次的检验记录，以及现在放不了行的原因（能放行时为空）。 */
    /** 检验结果导出：每次检验的每个项目一行。 */
    public record ExportRow(String inspectionNo, String batchNo, String itemCode, String itemName, String spec, LocalDate inspectedDate, String inspector, String testName,
                            String method, String limits, String result, String judgement, String conclusion, String status, String voidReason, String deviationNo, String note) {
    }

    public record Panel(SpecView spec, List<InspectionView> inspections, String blocker) {
    }

    @Transactional(readOnly = true)
    public List<SpecView> list() {
        return views(specs.findTop300ByOrderByIdDesc());
    }

    @Transactional(readOnly = true)
    public Optional<SpecView> effective(Long itemId) {
        return specs.findFirstByItemIdAndStatus(itemId, SpecStatus.EFFECTIVE).map(s -> views(List.of(s)).get(0));
    }

    @Transactional(readOnly = true)
    public List<SpecView> waitingSignature() {
        return views(specs.findByStatusOrderById(SpecStatus.PENDING));
    }

    /** 有生效标准的物料 id，上线检查用。 */
    @Transactional(readOnly = true)
    public Set<Long> itemsWithSpec() {
        return specs.findByStatusIn(List.of(SpecStatus.EFFECTIVE)).stream().map(QualitySpec::getItemId).collect(Collectors.toSet());
    }

    @Transactional
    public SpecView create(CreateReq req) {
        // 同一物料只能有一个待签批的版本：要先锁物料，而且是事务第一句
        Item item = items.lockById(req.itemId()).orElseThrow(() -> BizException.notFound("物料"));
        if (specs.findFirstByItemIdAndStatus(item.getId(), SpecStatus.PENDING).isPresent()) {
            throw new BizException(item.getName() + " 已有一个待签批的标准，签批或退回后再提交新版本");
        }
        Set<String> names = new HashSet<>();
        for (LineReq l : req.lines()) {
            if (!names.add(l.name().trim())) {
                throw new BizException("检测项目“" + l.name().trim() + "”重复了");
            }
            if (l.minValue() != null && l.maxValue() != null && l.minValue().compareTo(l.maxValue()) > 0) {
                throw new BizException("检测项目“" + l.name().trim() + "”的下限大于上限");
            }
        }
        QualitySpec s = new QualitySpec();
        s.setSpecNo(docNo.next("QS", time.today()));
        s.setItemId(item.getId());
        s.setVersionNo(specs.maxVersion(item.getId()) + 1);
        s.setStatus(SpecStatus.PENDING);
        s.setChangeNote(req.changeNote().trim());
        s.setCreatedBy(currentUser.usernameOrSystem());
        s.setCreatedAt(time.now());
        specs.save(s);
        int n = 1;
        for (LineReq l : req.lines()) {
            QualitySpecLine x = new QualitySpecLine();
            x.setSpecId(s.getId());
            x.setLineNo(n++);
            x.setName(l.name().trim());
            x.setMethod(blankToNull(l.method()));
            x.setUnit(blankToNull(l.unit()));
            x.setMinValue(l.minValue());
            x.setMaxValue(l.maxValue());
            x.setCriteria(blankToNull(l.criteria()));
            lines.save(x);
        }
        audit.log("提交质量标准", s.getSpecNo(), item.getName() + " V" + s.getVersionNo() + "，" + req.lines().size() + " 个项目：" + s.getChangeNote());
        return views(List.of(s)).get(0);
    }

    @Transactional
    public SpecView approve(Long id, ApproveReq req) {
        QualitySpec s = specs.lockById(id).orElseThrow(() -> BizException.notFound("质量标准"));
        requirePending(s, "签批");
        AppUser signer = signature.verify(req.password());
        if (signer.getUsername().equals(s.getCreatedBy())) {
            throw new BizException("签批人不能是提交人");
        }
        specs.findFirstByItemIdAndStatus(s.getItemId(), SpecStatus.EFFECTIVE).ifPresent(old -> {
            old.setStatus(SpecStatus.SUPERSEDED);
            specs.save(old);
        });
        s.setStatus(SpecStatus.EFFECTIVE);
        s.setApprovedBy(signer.getUsername());
        s.setApprovedAt(time.now());
        specs.save(s);
        audit.log("签批质量标准", s.getSpecNo(), lookup.item(s.getItemId()).getName() + " V" + s.getVersionNo() + " 生效");
        return views(List.of(s)).get(0);
    }

    @Transactional
    public SpecView reject(Long id, NoteReq req) {
        QualitySpec s = specs.lockById(id).orElseThrow(() -> BizException.notFound("质量标准"));
        requirePending(s, "退回");
        s.setStatus(SpecStatus.REJECTED);
        s.setRejectNote(req.note().trim());
        specs.save(s);
        audit.log("退回质量标准", s.getSpecNo(), lookup.item(s.getItemId()).getName() + " V" + s.getVersionNo(), req.note().trim());
        return views(List.of(s)).get(0);
    }

    @Transactional(readOnly = true)
    public Panel panel(Long batchId) {
        Batch b = batches.findById(batchId).orElseThrow(() -> BizException.notFound("批次"));
        return new Panel(effective(b.getItemId()).orElse(null), inspectionViews(inspections.findByBatchIdOrderByIdDesc(batchId)), blocker(b).orElse(null));
    }

    /** 放行前检查，调用方要已经锁了批次。没有生效标准的不检查。 */
    @Transactional(readOnly = true)
    public Optional<String> blocker(Batch b) {
        if (specs.findFirstByItemIdAndStatus(b.getItemId(), SpecStatus.EFFECTIVE).isEmpty()) {
            return Optional.empty();
        }
        Optional<BatchInspection> last = inspections.findFirstByBatchIdAndStatusOrderByIdDesc(b.getId(), InspectionStatus.VALID);
        if (last.isEmpty()) {
            return Optional.of(lookup.item(b.getItemId()).getName() + " 有生效的质量标准，请先录入检验结果");
        }
        if (!last.get().isPassed()) {
            return Optional.of("最近一次检验 " + last.get().getInspectionNo() + " 不合格，不能放行；偏差处理完后复检合格才能放行，或判定不合格");
        }
        return Optional.empty();
    }

    @Transactional
    public Panel record(Long batchId, InspectReq req) {
        Batch b = batches.lockById(batchId).orElseThrow(() -> BizException.notFound("批次"));
        requireBeforeRelease(b, "录入检验结果");
        Item item = lookup.item(b.getItemId());
        QualitySpec spec = specs.findFirstByItemIdAndStatus(item.getId(), SpecStatus.EFFECTIVE)
                .orElseThrow(() -> new BizException(item.getName() + " 还没有生效的质量标准，按检验报告编号放行即可"));
        if (req.inspectedDate().isAfter(time.today())) {
            throw new BizException("检验日期不能晚于今天");
        }
        if (b.getMfgDate() != null && req.inspectedDate().isBefore(b.getMfgDate())) {
            throw new BizException("检验日期不能早于生产日期 " + b.getMfgDate());
        }
        String note = blankToNull(req.note());
        if (note == null && inspections.existsByBatchIdAndStatusAndPassedFalse(b.getId(), InspectionStatus.VALID)) {
            throw new BizException("这批已有不合格的检验记录，复检请在备注里写明原因和依据");
        }
        List<QualitySpecLine> specLines = lines.findBySpecIdOrderByLineNo(spec.getId());
        Map<Long, ResultReq> byLine = new HashMap<>();
        for (ResultReq r : req.results()) {
            if (byLine.put(r.lineId(), r) != null) {
                throw new BizException("同一个检测项目填了两次");
            }
        }
        if (!specLines.stream().map(QualitySpecLine::getId).collect(Collectors.toSet()).containsAll(byLine.keySet())) {
            throw new BizException("检测项目不属于现行标准，标准可能刚更新过，请刷新后重填");
        }
        List<String> failed = new ArrayList<>();
        List<BatchInspectionResult> out = new ArrayList<>();
        for (QualitySpecLine l : specLines) {
            ResultReq r = byLine.get(l.getId());
            if (r == null) {
                throw new BizException("请填写“" + l.getName() + "”的结果");
            }
            boolean pass = judge(l, r);
            BatchInspectionResult x = new BatchInspectionResult();
            x.setSpecLineId(l.getId());
            x.setValueNum(r.value());
            x.setValueText(blankToNull(r.text()));
            x.setPassed(pass);
            out.add(x);
            if (!pass) {
                String limits = SpecLimit.text(l.getMinValue(), l.getMaxValue(), l.getUnit());
                failed.add(l.getName() + " " + shown(r, l) + (limits.isEmpty() ? "" : "（标准 " + limits + "）"));
            }
        }
        if (!failed.isEmpty() && note == null) {
            throw new BizException(String.join("、", failed) + " 不合格，请在备注里写明情况");
        }
        BatchInspection i = new BatchInspection();
        i.setInspectionNo(docNo.next("QI", time.today()));
        i.setBatchId(b.getId());
        i.setSpecId(spec.getId());
        i.setInspectedDate(req.inspectedDate());
        i.setInspector(currentUser.usernameOrSystem());
        i.setNote(note);
        i.setPassed(failed.isEmpty());
        i.setStatus(InspectionStatus.VALID);
        i.setCreatedAt(time.now());
        inspections.save(i);
        out.forEach(x -> x.setInspectionId(i.getId()));
        results.saveAll(out);
        String detail = item.getName() + "，按 " + spec.getSpecNo() + "（V" + spec.getVersionNo() + "）" + (failed.isEmpty() ? "，全部合格" : "，不合格：" + String.join("、", failed));
        if (!failed.isEmpty()) {
            String description = "批次 " + b.getBatchNo() + " 检验 " + i.getInspectionNo() + " 不合格：" + String.join("、", failed) + "。情况：" + note;
            Deviation d = deviationService.createForInspection(i.getInspectionNo(), b.getId(), req.inspectedDate(), item.getName() + " 批次 " + b.getBatchNo() + " 检验不合格",
                    description.length() <= 1000 ? description : description.substring(0, 1000));
            i.setDeviationId(d.getId());
            inspections.save(i);
            detail += "，偏差 " + d.getDeviationNo();
        }
        audit.log("录入检验结果", b.getBatchNo(), i.getInspectionNo() + "，" + detail);
        return panel(b.getId());
    }

    /** 录错了由 QSO 作废，记录保留，然后重录。放行以后不能作废。 */
    @Transactional
    public Panel voidInspection(Long batchId, Long inspectionId, NoteReq req) {
        Batch b = batches.lockById(batchId).orElseThrow(() -> BizException.notFound("批次"));
        requireBeforeRelease(b, "作废检验记录");
        BatchInspection i = inspections.findById(inspectionId).filter(x -> x.getBatchId().equals(b.getId())).orElseThrow(() -> BizException.notFound("检验记录"));
        if (i.getStatus() != InspectionStatus.VALID) {
            throw new BizException("检验记录 " + i.getInspectionNo() + " 已作废");
        }
        i.setStatus(InspectionStatus.VOID);
        i.setVoidReason(req.note().trim());
        i.setVoidedBy(currentUser.usernameOrSystem());
        inspections.save(i);
        audit.log("作废检验记录", b.getBatchNo(), i.getInspectionNo(), req.note().trim());
        return panel(b.getId());
    }

    private static void requireBeforeRelease(Batch b, String action) {
        if (b.getStatus() != BatchStatus.QUARANTINE && b.getStatus() != BatchStatus.PENDING_RELEASE) {
            throw new BizException("批次 " + b.getBatchNo() + " 当前为" + b.getStatus().getLabel() + "，不能" + action);
        }
    }

    private static boolean judge(QualitySpecLine l, ResultReq r) {
        if (SpecLimit.hasLimits(l.getMinValue(), l.getMaxValue())) {
            if (r.value() == null) {
                throw new BizException("“" + l.getName() + "”有限度（" + SpecLimit.text(l.getMinValue(), l.getMaxValue(), l.getUnit()) + "），请填写实测数值");
            }
            return SpecLimit.within(l.getMinValue(), l.getMaxValue(), r.value());
        }
        if (r.value() == null && (r.text() == null || r.text().isBlank())) {
            throw new BizException("请填写“" + l.getName() + "”的结果");
        }
        if (r.passed() == null) {
            throw new BizException("请判定“" + l.getName() + "”是否合格");
        }
        return r.passed();
    }

    private static String shown(ResultReq r, QualitySpecLine l) {
        String v = r.value() != null ? r.value().stripTrailingZeros().toPlainString() + (l.getUnit() == null ? "" : " " + l.getUnit()) : "";
        return r.text() == null || r.text().isBlank() ? v : (v.isEmpty() ? r.text().trim() : v + " " + r.text().trim());
    }

    private static void requirePending(QualitySpec s, String action) {
        if (s.getStatus() != SpecStatus.PENDING) {
            throw new BizException("标准 " + s.getSpecNo() + " 当前为" + s.getStatus().getLabel() + "，不能" + action);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private List<SpecView> views(List<QualitySpec> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, List<QualitySpecLine>> byspec = lines.findBySpecIdIn(list.stream().map(QualitySpec::getId).toList()).stream().collect(Collectors.groupingBy(QualitySpecLine::getSpecId));
        Map<Long, Item> itemMap = lookup.itemMap();
        return list.stream().map(s -> {
            Item i = itemMap.get(s.getItemId());
            List<LineView> lv = byspec.getOrDefault(s.getId(), List.of()).stream().sorted(java.util.Comparator.comparing(QualitySpecLine::getLineNo))
                    .map(l -> new LineView(l.getId(), l.getLineNo(), l.getName(), l.getMethod(), l.getUnit(), l.getMinValue(), l.getMaxValue(), l.getCriteria(),
                            SpecLimit.text(l.getMinValue(), l.getMaxValue(), l.getUnit()), SpecLimit.hasLimits(l.getMinValue(), l.getMaxValue()))).toList();
            return new SpecView(s.getId(), s.getSpecNo(), s.getItemId(), i.getCode(), i.getName(), i.getItemType().getLabel(), s.getVersionNo(), s.getStatus(), s.getStatus().getLabel(),
                    s.getChangeNote(), s.getRejectNote(), s.getCreatedBy(), s.getCreatedAt(), s.getApprovedBy(), s.getApprovedAt(), lv);
        }).toList();
    }

    private List<InspectionView> inspectionViews(List<BatchInspection> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, List<BatchInspectionResult>> byInspection = results.findByInspectionIdIn(list.stream().map(BatchInspection::getId).toList()).stream()
                .collect(Collectors.groupingBy(BatchInspectionResult::getInspectionId));
        Map<Long, QualitySpec> specMap = new HashMap<>();
        specs.findAllById(list.stream().map(BatchInspection::getSpecId).distinct().toList()).forEach(s -> specMap.put(s.getId(), s));
        Map<Long, String> devNos = new HashMap<>();
        deviations.findAllById(list.stream().map(BatchInspection::getDeviationId).filter(java.util.Objects::nonNull).toList()).forEach(d -> devNos.put(d.getId(), d.getDeviationNo()));
        return list.stream().map(i -> {
            QualitySpec s = specMap.get(i.getSpecId());
            return new InspectionView(i.getId(), i.getInspectionNo(), s.getId(), s.getSpecNo(), s.getVersionNo(), i.getInspectedDate(), i.getInspector(), i.getNote(), i.isPassed(),
                    i.getStatus(), i.getStatus().getLabel(), i.getVoidReason(), i.getVoidedBy(), i.getDeviationId(), devNos.get(i.getDeviationId()), i.getCreatedAt(),
                    byInspection.getOrDefault(i.getId(), List.of()).stream().map(r -> new ResultView(r.getSpecLineId(), r.getValueNum(), r.getValueText(), r.isPassed())).toList());
        }).toList();
    }

    /** 全部检验结果，导出用；from、to 按检验日期过滤，都可以为空。不用页面的查询。 */
    @Transactional(readOnly = true)
    public List<ExportRow> exportRows(LocalDate from, LocalDate to) {
        List<BatchInspection> all = inspections.findAllByOrderByIdAsc().stream()
                .filter(i -> (from == null || !i.getInspectedDate().isBefore(from)) && (to == null || !i.getInspectedDate().isAfter(to))).toList();
        if (all.isEmpty()) {
            return List.of();
        }
        Map<Long, QualitySpec> specMap = new HashMap<>();
        specs.findAllById(all.stream().map(BatchInspection::getSpecId).distinct().toList()).forEach(x -> specMap.put(x.getId(), x));
        Map<Long, List<QualitySpecLine>> linesBySpec = lines.findBySpecIdIn(specMap.keySet()).stream().collect(Collectors.groupingBy(QualitySpecLine::getSpecId));
        Map<Long, List<BatchInspectionResult>> resultsBy = results.findByInspectionIdIn(all.stream().map(BatchInspection::getId).toList()).stream()
                .collect(Collectors.groupingBy(BatchInspectionResult::getInspectionId));
        Map<Long, Batch> batchMap = new HashMap<>();
        batches.findAllById(all.stream().map(BatchInspection::getBatchId).distinct().toList()).forEach(b -> batchMap.put(b.getId(), b));
        Map<Long, String> devNos = new HashMap<>();
        deviations.findAllById(all.stream().map(BatchInspection::getDeviationId).filter(java.util.Objects::nonNull).distinct().toList())
                .forEach(d -> devNos.put(d.getId(), d.getDeviationNo()));
        Map<Long, Item> items = lookup.itemMap();
        List<ExportRow> out = new ArrayList<>();
        for (BatchInspection i : all) {
            Batch b = batchMap.get(i.getBatchId());
            Item item = items.get(b.getItemId());
            QualitySpec sp = specMap.get(i.getSpecId());
            Map<Long, BatchInspectionResult> rs = resultsBy.getOrDefault(i.getId(), List.of()).stream().collect(Collectors.toMap(BatchInspectionResult::getSpecLineId, r -> r, (x, y) -> x));
            for (QualitySpecLine l : linesBySpec.getOrDefault(sp.getId(), List.of()).stream().sorted(java.util.Comparator.comparing(QualitySpecLine::getLineNo)).toList()) {
                BatchInspectionResult r = rs.get(l.getId());
                String limits = SpecLimit.text(l.getMinValue(), l.getMaxValue(), l.getUnit());
                String shown = r == null ? null : ((r.getValueNum() == null ? "" : r.getValueNum().stripTrailingZeros().toPlainString() + (l.getUnit() == null ? "" : " " + l.getUnit()))
                        + (r.getValueText() == null ? "" : (r.getValueNum() == null ? "" : " ") + r.getValueText())).trim();
                out.add(new ExportRow(i.getInspectionNo(), b.getBatchNo(), item.getCode(), item.getName(), sp.getSpecNo() + " V" + sp.getVersionNo(), i.getInspectedDate(),
                        i.getInspector(), l.getName(), l.getMethod(), limits.isEmpty() ? l.getCriteria() : limits, shown, r == null ? null : r.isPassed() ? "合格" : "不合格",
                        i.isPassed() ? "合格" : "不合格", i.getStatus().getLabel(), i.getVoidReason(), devNos.get(i.getDeviationId()), i.getNote()));
            }
        }
        return out;
    }
}
