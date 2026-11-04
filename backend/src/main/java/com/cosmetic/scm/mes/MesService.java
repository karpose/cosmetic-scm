package com.cosmetic.scm.mes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.ClearancePhase;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.MoStatus;
import com.cosmetic.scm.domain.MoType;
import com.cosmetic.scm.domain.StepStatus;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.production.MfgOrder;
import com.cosmetic.scm.production.MfgOrderRepository;
import com.cosmetic.scm.qms.Deviation;
import com.cosmetic.scm.qms.DeviationRepository;
import com.cosmetic.scm.qms.DeviationService;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.CurrentUser;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 自产的电子批生产记录。发料时把工艺路线固化到工单上（之后改路线不影响这张单），然后：
 * 开工前清场 → 按顺序执行工序、填过程检验 → 关键工序他人复核 → 完工后清场 → 才能完工入库。
 * 过程检验超限不阻止继续生产，但自动生成重大偏差：偏差没关闭，这批不能上市放行。
 * 没有维护工艺路线的成品不生成批记录，也不受这些限制；委托生产的批记录在受托厂，不走这里。
 */
@Service
@RequiredArgsConstructor
public class MesService {

    private final RoutingStepRepository routing;
    private final BatchRecordStepRepository steps;
    private final LineClearanceRepository clearances;
    private final MfgOrderRepository orders;
    private final DeviationService deviationService;
    private final DeviationRepository deviations;
    private final MasterLookup lookup;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record RoutingStepReq(@NotBlank(message = "请填写工序名称") @Size(max = 100) String name,
                                 @Size(max = 500) String instruction,
                                 boolean critical,
                                 @Size(max = 60) String ipcName,
                                 BigDecimal ipcMin,
                                 BigDecimal ipcMax,
                                 @Size(max = 20) String ipcUnit) {
    }

    public record RoutingReq(@NotEmpty(message = "至少一道工序") @Valid List<RoutingStepReq> steps) {
    }

    public record RoutingStepView(Long id, int stepNo, String name, String instruction, boolean critical, String ipcName,
                                  BigDecimal ipcMin, BigDecimal ipcMax, String ipcUnit) {
    }

    public record ClearanceReq(@NotNull ClearancePhase phase,
                               @NotBlank(message = "请填写生产区域或设备") @Size(max = 100) String area,
                               @Size(max = 100) String prevProduct,
                               @AssertTrue(message = "请确认上批物料、中间产品已清离现场") boolean materialsCleared,
                               @AssertTrue(message = "请确认设备和工器具已清洁") boolean equipmentCleaned,
                               @AssertTrue(message = "请确认上批文件和标识已清离现场") boolean documentsCleared,
                               @Size(max = 300) String note,
                               @NotBlank(message = "请输入签批密码") String password) {
    }

    public record StepReq(BigDecimal ipcValue, @Size(max = 500) String note) {
    }

    public record ReviewReq(@NotBlank(message = "请输入签批密码") String password) {
    }

    public record StepView(int stepNo, String name, String instruction, boolean critical, String ipcName, BigDecimal ipcMin,
                           BigDecimal ipcMax, String ipcUnit, StepStatus status, String statusLabel, BigDecimal ipcValue,
                           Boolean ipcPass, String note, String operator, LocalDateTime doneAt, String reviewer,
                           LocalDateTime reviewedAt, Long deviationId, String deviationNo) {
    }

    public record ClearanceView(ClearancePhase phase, String phaseLabel, String area, String prevProduct, String note,
                                String signedBy, LocalDateTime signedAt) {
    }

    /** hasRecord 为 false 表示这张单没有批记录（委托生产、未发料、或成品没有维护工艺路线）。 */
    public record RecordView(Long moId, String moNo, MoStatus moStatus, boolean hasRecord, List<StepView> steps,
                             List<ClearanceView> clearances, List<String> missing) {
    }

    // ---------- 工艺路线 ----------

    @Transactional(readOnly = true)
    public List<RoutingStepView> routing(Long fgItemId) {
        return routing.findByFgItemIdOrderByStepNo(fgItemId).stream()
                .map(r -> new RoutingStepView(r.getId(), r.getStepNo(), r.getName(), r.getInstruction(), r.isCritical(),
                        r.getIpcName(), r.getIpcMin(), r.getIpcMax(), r.getIpcUnit())).toList();
    }

    @Transactional
    public List<RoutingStepView> saveRouting(Long fgItemId, RoutingReq req) {
        Item fg = lookup.item(fgItemId);
        if (fg.getItemType() != ItemType.FG) {
            throw new BizException(fg.getName() + " 不是成品");
        }
        int no = 0;
        for (RoutingStepReq s : req.steps()) {
            no++;
            boolean hasName = s.ipcName() != null && !s.ipcName().isBlank();
            if (hasName && s.ipcMin() == null && s.ipcMax() == null) {
                throw new BizException("第 " + no + " 道工序的过程检验“" + s.ipcName().trim() + "”请至少填写下限或上限");
            }
            if (!hasName && (s.ipcMin() != null || s.ipcMax() != null)) {
                throw new BizException("第 " + no + " 道工序填了限度，请写明检验项目");
            }
            if (s.ipcMin() != null && s.ipcMax() != null && s.ipcMin().compareTo(s.ipcMax()) > 0) {
                throw new BizException("第 " + no + " 道工序的下限大于上限");
            }
        }
        routing.deleteByFgItemId(fgItemId);
        routing.flush();
        no = 1;
        for (RoutingStepReq s : req.steps()) {
            RoutingStep r = new RoutingStep();
            r.setFgItemId(fgItemId);
            r.setStepNo(no++);
            r.setName(s.name().trim());
            r.setInstruction(blankToNull(s.instruction()));
            r.setCritical(s.critical());
            r.setIpcName(blankToNull(s.ipcName()));
            r.setIpcMin(s.ipcMin());
            r.setIpcMax(s.ipcMax());
            r.setIpcUnit(blankToNull(s.ipcUnit()));
            routing.save(r);
        }
        audit.log("保存工艺路线", fg.getCode(), req.steps().size() + " 道工序：" + String.join(" → ", req.steps().stream().map(RoutingStepReq::name).toList()));
        return routing(fgItemId);
    }

    // ---------- 批生产记录 ----------

    /** 发料时调用：自产工单把工艺路线固化下来。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createRecord(MfgOrder mo) {
        if (mo.getMoType() != MoType.OWN) {
            return;
        }
        for (RoutingStep r : routing.findByFgItemIdOrderByStepNo(mo.getFgItemId())) {
            BatchRecordStep s = new BatchRecordStep();
            s.setMoId(mo.getId());
            s.setStepNo(r.getStepNo());
            s.setName(r.getName());
            s.setInstruction(r.getInstruction());
            s.setCritical(r.isCritical());
            s.setIpcName(r.getIpcName());
            s.setIpcMin(r.getIpcMin());
            s.setIpcMax(r.getIpcMax());
            s.setIpcUnit(r.getIpcUnit());
            s.setStatus(StepStatus.PENDING);
            steps.save(s);
        }
    }

    /** 是否已经开工：清过场或做过工序。开工后不能取消。 */
    @Transactional(readOnly = true)
    public boolean started(Long moId) {
        return !clearances.findByMoId(moId).isEmpty()
                || steps.findByMoIdOrderByStepNo(moId).stream().anyMatch(s -> s.getStatus() != StepStatus.PENDING);
    }

    /** 完工入库前还缺什么；没有批记录的单子返回空。 */
    @Transactional(readOnly = true)
    public List<String> missing(Long moId) {
        List<BatchRecordStep> list = steps.findByMoIdOrderByStepNo(moId);
        if (list.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (!clearances.existsByMoIdAndPhase(moId, ClearancePhase.BEFORE)) {
            out.add("开工前清场未确认");
        }
        for (BatchRecordStep s : list) {
            if (s.getStatus() == StepStatus.PENDING) {
                out.add("第 " + s.getStepNo() + " 道工序“" + s.getName() + "”未执行");
            } else if (s.isCritical() && s.getStatus() != StepStatus.REVIEWED) {
                out.add("关键工序“" + s.getName() + "”未复核");
            }
        }
        if (!clearances.existsByMoIdAndPhase(moId, ClearancePhase.AFTER)) {
            out.add("完工后清场未确认");
        }
        return out;
    }

    @Transactional(readOnly = true)
    public RecordView record(Long moId) {
        MfgOrder mo = requireMo(moId);
        return toView(mo);
    }

    @Transactional
    public RecordView clearance(Long moId, ClearanceReq req, AppUser signer) {
        MfgOrder mo = requireRunning(moId);
        List<BatchRecordStep> list = requireRecord(mo);
        if (clearances.existsByMoIdAndPhase(moId, req.phase())) {
            throw new BizException(req.phase().getLabel() + "已确认过");
        }
        if (req.phase() == ClearancePhase.AFTER) {
            boolean allDone = list.stream().allMatch(s -> s.getStatus() == StepStatus.REVIEWED
                    || s.getStatus() == StepStatus.DONE && !s.isCritical());
            if (!allDone) {
                throw new BizException("工序全部执行、关键工序复核后才能做完工后清场");
            }
        }
        LineClearance c = new LineClearance();
        c.setMoId(moId);
        c.setPhase(req.phase());
        c.setArea(req.area().trim());
        c.setPrevProduct(blankToNull(req.prevProduct()));
        c.setNote(blankToNull(req.note()));
        c.setSignedBy(signer.getDisplayName());
        c.setSignedAt(time.now());
        clearances.save(c);
        audit.log(req.phase().getLabel(), mo.getMoNo(), c.getArea() + "，确认人 " + signer.getDisplayName(), c.getNote());
        return toView(mo);
    }

    /** 执行一道工序：必须清过场、前面的工序做完（关键工序已复核）。检验超限也记录，并生成偏差。 */
    @Transactional
    public RecordView recordStep(Long moId, int stepNo, StepReq req) {
        MfgOrder mo = requireRunning(moId);
        List<BatchRecordStep> list = requireRecord(mo);
        if (!clearances.existsByMoIdAndPhase(moId, ClearancePhase.BEFORE)) {
            throw new BizException("请先完成开工前清场确认");
        }
        BatchRecordStep s = list.stream().filter(x -> x.getStepNo() == stepNo).findFirst()
                .orElseThrow(() -> BizException.notFound("工序"));
        if (s.getStatus() != StepStatus.PENDING) {
            throw new BizException("第 " + stepNo + " 道工序已执行，记录不能修改");
        }
        for (BatchRecordStep prev : list) {
            if (prev.getStepNo() < stepNo) {
                if (prev.getStatus() == StepStatus.PENDING) {
                    throw new BizException("请按顺序执行：第 " + prev.getStepNo() + " 道工序“" + prev.getName() + "”还没做");
                }
                if (prev.isCritical() && prev.getStatus() != StepStatus.REVIEWED) {
                    throw new BizException("关键工序“" + prev.getName() + "”还没复核，复核后才能继续");
                }
            }
        }
        String operator = currentUser.usernameOrSystem();
        String detail = s.getName();
        Deviation deviation = null;
        if (s.getIpcName() != null) {
            if (req.ipcValue() == null) {
                throw new BizException("请填写过程检验“" + s.getIpcName() + "”的实测值");
            }
            boolean pass = (s.getIpcMin() == null || req.ipcValue().compareTo(s.getIpcMin()) >= 0)
                    && (s.getIpcMax() == null || req.ipcValue().compareTo(s.getIpcMax()) <= 0);
            s.setIpcValue(req.ipcValue());
            s.setIpcPass(pass);
            detail += "，" + s.getIpcName() + " " + plain(req.ipcValue()) + (s.getIpcUnit() == null ? "" : " " + s.getIpcUnit()) + (pass ? "" : "（超限）");
            if (!pass) {
                if (req.note() == null || req.note().isBlank()) {
                    throw new BizException(s.getIpcName() + " 超出限度（" + limits(s) + "），请在备注里写明现场情况");
                }
                deviation = deviationService.createForOrder(mo, s.getName() + "：" + s.getIpcName() + " 超限",
                        "实测 " + plain(req.ipcValue()) + (s.getIpcUnit() == null ? "" : " " + s.getIpcUnit())
                                + "，限度 " + limits(s) + "。现场情况：" + req.note().trim());
                s.setDeviationId(deviation.getId());
                detail += "，偏差 " + deviation.getDeviationNo();
            }
        }
        s.setNote(blankToNull(req.note()));
        s.setOperator(operator);
        s.setDoneAt(time.now());
        s.setStatus(StepStatus.DONE);
        steps.save(s);
        audit.log("批记录执行工序", mo.getMoNo(), "第 " + stepNo + " 道：" + detail, s.getNote());
        return toView(mo);
    }

    /** 关键工序复核：复核人不能是操作人。 */
    @Transactional
    public RecordView review(Long moId, int stepNo, AppUser signer) {
        MfgOrder mo = requireRunning(moId);
        BatchRecordStep s = steps.findByMoIdAndStepNo(moId, stepNo).orElseThrow(() -> BizException.notFound("工序"));
        if (!s.isCritical()) {
            throw new BizException("第 " + stepNo + " 道工序不是关键工序，不需要复核");
        }
        if (s.getStatus() != StepStatus.DONE) {
            throw new BizException("第 " + stepNo + " 道工序当前为" + s.getStatus().getLabel() + "，不能复核");
        }
        if (signer.getUsername().equals(s.getOperator())) {
            throw new BizException("复核要由操作人以外的人进行");
        }
        s.setReviewer(signer.getDisplayName());
        s.setReviewedAt(time.now());
        s.setStatus(StepStatus.REVIEWED);
        steps.save(s);
        audit.log("批记录复核工序", mo.getMoNo(), "第 " + stepNo + " 道：" + s.getName() + "，复核人 " + signer.getDisplayName());
        return toView(mo);
    }

    // ---------- 内部 ----------

    private MfgOrder requireMo(Long moId) {
        return orders.findById(moId).orElseThrow(() -> BizException.notFound("生产单"));
    }

    private MfgOrder requireRunning(Long moId) {
        MfgOrder mo = requireMo(moId);
        if (mo.getStatus() != MoStatus.ISSUED) {
            throw new BizException(mo.getMoNo() + " 当前为" + mo.getStatus().getLabel() + "，批记录只能在已发料、未入库时填写");
        }
        return mo;
    }

    private List<BatchRecordStep> requireRecord(MfgOrder mo) {
        List<BatchRecordStep> list = steps.findByMoIdOrderByStepNo(mo.getId());
        if (list.isEmpty()) {
            throw new BizException(mo.getMoNo() + " 没有批生产记录（委托生产，或发料时成品还没有工艺路线）");
        }
        return list;
    }

    private RecordView toView(MfgOrder mo) {
        List<BatchRecordStep> list = steps.findByMoIdOrderByStepNo(mo.getId());
        List<StepView> stepViews = list.stream().map(s -> new StepView(s.getStepNo(), s.getName(), s.getInstruction(), s.isCritical(),
                s.getIpcName(), s.getIpcMin(), s.getIpcMax(), s.getIpcUnit(), s.getStatus(), s.getStatus().getLabel(), s.getIpcValue(),
                s.getIpcPass(), s.getNote(), s.getOperator(), s.getDoneAt(), s.getReviewer(), s.getReviewedAt(), s.getDeviationId(),
                s.getDeviationId() == null ? null : deviations.findById(s.getDeviationId()).map(Deviation::getDeviationNo).orElse(null))).toList();
        List<ClearanceView> clearanceViews = clearances.findByMoId(mo.getId()).stream()
                .map(c -> new ClearanceView(c.getPhase(), c.getPhase().getLabel(), c.getArea(), c.getPrevProduct(), c.getNote(), c.getSignedBy(), c.getSignedAt()))
                .toList();
        return new RecordView(mo.getId(), mo.getMoNo(), mo.getStatus(), !list.isEmpty(), stepViews, clearanceViews, missing(mo.getId()));
    }

    private static String limits(BatchRecordStep s) {
        String unit = s.getIpcUnit() == null ? "" : " " + s.getIpcUnit();
        if (s.getIpcMin() != null && s.getIpcMax() != null) {
            return plain(s.getIpcMin()) + "–" + plain(s.getIpcMax()) + unit;
        }
        return s.getIpcMin() != null ? "不低于 " + plain(s.getIpcMin()) + unit : "不高于 " + plain(s.getIpcMax()) + unit;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
