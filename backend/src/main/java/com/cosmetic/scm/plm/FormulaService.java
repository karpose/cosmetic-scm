package com.cosmetic.scm.plm;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.FilingStatus;
import com.cosmetic.scm.domain.FormulaStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.MoStatus;
import com.cosmetic.scm.domain.rules.FormulaDiff;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterDtos.BomLineReq;
import com.cosmetic.scm.master.MasterDtos.BomLineView;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.production.MfgOrder;
import com.cosmetic.scm.production.MfgOrderRepository;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.CurrentUser;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 配方版本管理。成品的第一版配方在主数据里录入后直接生效（上线初始化）；之后的任何修改都要走
 * 草稿 → 提交 → 质量安全负责人签批：原料有变化的，还必须先完成对应版本的备案。
 * 生效时写入 bom / bom_line，所以补货、成本等读当前 BOM 的模块不受影响；
 * 还没发料的生产单改用新版本，已发料的单子保留当时的版本。
 */
@Service
@RequiredArgsConstructor
public class FormulaService {

    private static final List<FormulaStatus> IN_PROGRESS = List.of(FormulaStatus.DRAFT, FormulaStatus.PENDING);

    private final FormulaVersionRepository versions;
    private final FormulaLineRepository lines;
    private final ProductFilingRepository filings;
    private final MfgOrderRepository orders;
    private final MasterService master;
    private final MasterLookup lookup;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record DraftReq(@NotBlank(message = "请填写变更内容和原因") @Size(max = 500) String changeNote,
                           @NotEmpty(message = "配方至少要有一行") @Valid List<BomLineReq> lines) {
    }

    public record ApproveReq(@NotBlank(message = "请输入签批密码") String password) {
    }

    public record RejectReq(@NotBlank(message = "请填写退回原因") @Size(max = 300) String reason,
                            @NotBlank(message = "请输入签批密码") String password) {
    }

    public record ChangeView(Long itemId, String itemCode, String itemName, String unit, String kind, String kindLabel,
                             BigDecimal oldQty, BigDecimal newQty, boolean affectsFormula) {
    }

    /** needsFiling：相对生效版本改了原料；filingNo：这个版本已完成的备案编号，还没有则为空。 */
    public record VersionView(Long id, Long fgItemId, String fgCode, String fgName, int versionNo, FormulaStatus status,
                              String statusLabel, String changeNote, String rejectNote, String createdBy,
                              LocalDateTime createdAt, LocalDateTime submittedAt, String approvedBy,
                              LocalDateTime approvedAt, List<BomLineView> lines, List<ChangeView> changes,
                              boolean needsFiling, String filingNo) {
    }

    @Transactional(readOnly = true)
    public List<VersionView> list() {
        return versions.findTop300ByOrderByIdDesc().stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return versions.findByStatus(FormulaStatus.PENDING).size();
    }

    @Transactional
    public VersionView createDraft(Long fgItemId, DraftReq req) {
        Item fg = requireFg(fgItemId);
        if (versions.existsByFgItemIdAndStatusIn(fgItemId, IN_PROGRESS)) {
            throw new BizException(fg.getName() + " 已有一个进行中的配方变更，请先处理完再发起新的");
        }
        master.validateBomLines(fg, req.lines());
        int next = versions.findByFgItemIdOrderByVersionNoDesc(fgItemId).stream()
                .mapToInt(FormulaVersion::getVersionNo).max().orElse(0) + 1;
        FormulaVersion v = new FormulaVersion();
        v.setFgItemId(fgItemId);
        v.setVersionNo(next);
        v.setStatus(FormulaStatus.DRAFT);
        v.setChangeNote(req.changeNote().trim());
        v.setCreatedBy(currentUser.usernameOrSystem());
        v.setCreatedAt(time.now());
        versions.save(v);
        saveLines(v, req.lines());
        audit.log("新建配方变更", fg.getCode() + " V" + next, describe(req.lines()), v.getChangeNote());
        return toView(v);
    }

    @Transactional
    public VersionView updateDraft(Long id, DraftReq req) {
        FormulaVersion v = require(id);
        requireStatus(v, FormulaStatus.DRAFT, "修改");
        Item fg = requireFg(v.getFgItemId());
        master.validateBomLines(fg, req.lines());
        v.setChangeNote(req.changeNote().trim());
        versions.save(v);
        lines.deleteByVersionId(v.getId());
        lines.flush();
        saveLines(v, req.lines());
        audit.log("修改配方变更", fg.getCode() + " V" + v.getVersionNo(), describe(req.lines()), v.getChangeNote());
        return toView(v);
    }

    @Transactional
    public VersionView submit(Long id) {
        FormulaVersion v = require(id);
        requireStatus(v, FormulaStatus.DRAFT, "提交");
        Item fg = requireFg(v.getFgItemId());
        if (changesOf(v).isEmpty()) {
            throw new BizException("和当前生效版本没有区别，不需要提交");
        }
        master.validateBomLines(fg, reqLines(v));
        v.setStatus(FormulaStatus.PENDING);
        v.setSubmittedAt(time.now());
        v.setRejectNote(null);
        versions.save(v);
        audit.log("提交配方变更", fg.getCode() + " V" + v.getVersionNo(), v.getChangeNote());
        return toView(v);
    }

    /** 质量安全负责人签批生效。提交人不能批准自己的变更；配方合规在这里再校验一次（物料的限用信息可能已变）。 */
    @Transactional
    public VersionView approve(Long id, AppUser signer) {
        FormulaVersion v = require(id);
        requireStatus(v, FormulaStatus.PENDING, "签批");
        if (signer.getUsername().equals(v.getCreatedBy())) {
            throw new BizException("配方变更不能由发起人本人批准");
        }
        Item fg = requireFg(v.getFgItemId());
        List<FormulaDiff.Change> changes = FormulaDiff.diff(toDiffLines(effectiveLines(v.getFgItemId())), toDiffLines(linesOf(v)));
        if (FormulaDiff.needsFiling(changes) && filings.findByFormulaVersionIdAndStatus(v.getId(), FilingStatus.FILED).isEmpty()) {
            throw new BizException("这个版本改了原料，产品配方发生变化，请先在“备案登记”完成 V" + v.getVersionNo() + " 的备案，再签批生效");
        }
        List<BomLineReq> req = reqLines(v);
        master.validateBomLines(fg, req);
        master.applyBom(fg, req);

        versions.findFirstByFgItemIdAndStatus(v.getFgItemId(), FormulaStatus.EFFECTIVE).ifPresent(old -> {
            old.setStatus(FormulaStatus.RETIRED);
            versions.save(old);
        });
        v.setStatus(FormulaStatus.EFFECTIVE);
        v.setApprovedBy(signer.getDisplayName());
        v.setApprovedAt(time.now());
        versions.save(v);

        List<String> moved = new ArrayList<>();
        for (MfgOrder m : orders.findByStatusIn(List.of(MoStatus.PLANNED))) {
            if (m.getFgItemId().equals(v.getFgItemId())) {
                m.setFormulaVersionId(v.getId());
                orders.save(m);
                moved.add(m.getMoNo());
            }
        }
        audit.log("配方变更生效", fg.getCode() + " V" + v.getVersionNo(), "签批人 " + signer.getDisplayName()
                + (moved.isEmpty() ? "" : "；未发料的生产单改用新版本：" + String.join("、", moved)), v.getChangeNote());
        return toView(v);
    }

    @Transactional
    public VersionView reject(Long id, RejectReq req, AppUser signer) {
        FormulaVersion v = require(id);
        requireStatus(v, FormulaStatus.PENDING, "退回");
        v.setStatus(FormulaStatus.DRAFT);
        v.setRejectNote(req.reason().trim());
        versions.save(v);
        audit.log("退回配方变更", lookup.item(v.getFgItemId()).getCode() + " V" + v.getVersionNo(),
                "签批人 " + signer.getDisplayName(), v.getRejectNote());
        return toView(v);
    }

    /** 不做这次变更了。版本号不复用，记录保留。 */
    @Transactional
    public VersionView discard(Long id) {
        FormulaVersion v = require(id);
        requireStatus(v, FormulaStatus.DRAFT, "作废");
        v.setStatus(FormulaStatus.DISCARDED);
        versions.save(v);
        audit.log("作废配方变更", lookup.item(v.getFgItemId()).getCode() + " V" + v.getVersionNo(), v.getChangeNote());
        return toView(v);
    }

    // ---------- 内部 ----------

    private void saveLines(FormulaVersion v, List<BomLineReq> req) {
        int no = 1;
        for (BomLineReq l : req) {
            FormulaLine line = new FormulaLine();
            line.setVersionId(v.getId());
            line.setLineNo(no++);
            line.setItemId(l.itemId());
            line.setQtyPer(l.qtyPer());
            line.setSupplyMode(l.supplyMode());
            lines.save(line);
        }
    }

    private List<FormulaLine> linesOf(FormulaVersion v) {
        return lines.findByVersionIdOrderByLineNo(v.getId());
    }

    private List<FormulaLine> effectiveLines(Long fgItemId) {
        return versions.findFirstByFgItemIdAndStatus(fgItemId, FormulaStatus.EFFECTIVE)
                .map(this::linesOf).orElse(List.of());
    }

    private List<BomLineReq> reqLines(FormulaVersion v) {
        return linesOf(v).stream().map(l -> new BomLineReq(l.getItemId(), l.getQtyPer(), l.getSupplyMode())).toList();
    }

    private List<FormulaDiff.Line> toDiffLines(List<FormulaLine> list) {
        Map<Long, Item> im = lookup.itemMap();
        return list.stream().map(l -> new FormulaDiff.Line(l.getItemId(), l.getQtyPer(), l.getSupplyMode().name(),
                im.get(l.getItemId()).getItemType() == ItemType.RM)).toList();
    }

    /** 草稿和待签批的版本相对当前生效版本的变化；其它状态已成历史，不再比较。 */
    private List<FormulaDiff.Change> changesOf(FormulaVersion v) {
        if (!IN_PROGRESS.contains(v.getStatus())) {
            return List.of();
        }
        return FormulaDiff.diff(toDiffLines(effectiveLines(v.getFgItemId())), toDiffLines(linesOf(v)));
    }

    private VersionView toView(FormulaVersion v) {
        Map<Long, Item> im = lookup.itemMap();
        Item fg = im.get(v.getFgItemId());
        List<BomLineView> lineViews = linesOf(v).stream()
                .map(l -> master.lineView(fg, im.get(l.getItemId()), l.getQtyPer(), l.getSupplyMode())).toList();
        List<FormulaDiff.Change> changes = changesOf(v);
        List<ChangeView> changeViews = changes.stream().map(c -> {
            Item i = im.get(c.itemId());
            return new ChangeView(i.getId(), i.getCode(), i.getName(), i.getUnit(), c.kind().name(), kindLabel(c.kind()),
                    c.oldQty(), c.newQty(), c.affectsFormula());
        }).toList();
        String filingNo = filings.findByFormulaVersionIdAndStatus(v.getId(), FilingStatus.FILED).stream()
                .map(ProductFiling::getFilingNo).findFirst().orElse(null);
        return new VersionView(v.getId(), v.getFgItemId(), fg.getCode(), fg.getName(), v.getVersionNo(), v.getStatus(),
                v.getStatus().getLabel(), v.getChangeNote(), v.getRejectNote(), v.getCreatedBy(), v.getCreatedAt(),
                v.getSubmittedAt(), v.getApprovedBy(), v.getApprovedAt(), lineViews, changeViews,
                FormulaDiff.needsFiling(changes), filingNo);
    }

    private static String kindLabel(FormulaDiff.Kind k) {
        return switch (k) {
            case ADDED -> "新增";
            case REMOVED -> "删除";
            case QTY_CHANGED -> "用量变化";
            case SUPPLY_CHANGED -> "供料方式变化";
        };
    }

    private String describe(List<BomLineReq> req) {
        Map<Long, Item> im = lookup.itemMap();
        return req.stream().map(l -> im.get(l.itemId()).getCode() + "×" + l.qtyPer().stripTrailingZeros().toPlainString())
                .collect(Collectors.joining("，"));
    }

    private Item requireFg(Long id) {
        Item fg = lookup.item(id);
        if (fg.getItemType() != ItemType.FG) {
            throw new BizException(fg.getName() + " 不是成品");
        }
        return fg;
    }

    private FormulaVersion require(Long id) {
        return versions.lockById(id).orElseThrow(() -> BizException.notFound("配方版本"));
    }

    private static void requireStatus(FormulaVersion v, FormulaStatus expected, String action) {
        if (v.getStatus() != expected) {
            throw new BizException("V" + v.getVersionNo() + " 当前为" + v.getStatus().getLabel() + "，不能" + action);
        }
    }
}
