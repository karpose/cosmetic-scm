package com.cosmetic.scm.plm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.ComplianceDocStatus;
import com.cosmetic.scm.domain.ComplianceDocType;
import com.cosmetic.scm.domain.FormulaStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.rules.FormulaCompliance;
import com.cosmetic.scm.master.BomLine;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.CurrentUser;
import com.cosmetic.scm.security.SignatureService;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 产品合规资料：安全评估、功效宣称依据、标签，都对应配方版本。配方变了，旧版本的资料就要重新评估。
 * 登记后由质量安全负责人签批，不能是登记人。报告文件作为附件，系统不判断报告内容对不对。
 */
@Service
@RequiredArgsConstructor
public class ComplianceService {

    private static final BigDecimal LABEL_ORDER_LINE = new BigDecimal("0.1");

    private final ComplianceDocRepository docs;
    private final FormulaVersionRepository versions;
    private final MasterService master;
    private final MasterLookup lookup;
    private final SignatureService signature;
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record CreateReq(@NotNull(message = "请选择成品") Long fgItemId, @NotNull(message = "请选择资料类型") ComplianceDocType docType,
                            Long formulaVersionId, @NotBlank(message = "请填写标题") @Size(max = 120) String title,
                            @Size(max = 300) String claim, @NotBlank(message = "请填写评估人或出具机构") @Size(max = 100) String evaluator,
                            @NotNull(message = "请选择日期") LocalDate docDate, @Size(max = 500) String note) {
    }

    public record ApproveReq(@NotBlank(message = "请输入签批密码") String password) {
    }

    public record NoteReq(@NotBlank(message = "请写明原因") @Size(max = 300) String note) {
    }

    public record DocView(Long id, String docNo, Long fgItemId, String fgCode, String fgName, Long formulaVersionId, Integer versionNo, ComplianceDocType docType,
                          String docTypeLabel, String title, String claim, String evaluator, LocalDate docDate, String note, ComplianceDocStatus status, String statusLabel,
                          String rejectNote, String voidReason, String signedBy, LocalDateTime signedAt, String createdBy, LocalDateTime createdAt) {
    }

    /** 状态：OK 当前版本已签批；STALE 有旧版本的，配方变了要重新评估；PENDING 待签批；MISSING 没有。 */
    public record ProductStatus(Long fgItemId, String fgCode, String fgName, Long versionId, Integer versionNo, String safety, String efficacy, String label) {
        public boolean complete() {
            return "OK".equals(safety) && "OK".equals(label);
        }
    }

    public record Ingredient(String itemCode, String itemName, String inci, BigDecimal pct) {
    }

    /** 按含量排序的全成分表草稿。problems 是生成时发现的问题，要人工核对。 */
    public record IngredientList(Long fgItemId, String fgName, Integer versionNo, List<Ingredient> lines, String text, List<String> problems) {
    }

    @Transactional(readOnly = true)
    public List<DocView> list() {
        return views(docs.findTop300ByOrderByIdDesc());
    }

    @Transactional(readOnly = true)
    public List<ProductStatus> statuses() {
        List<Item> fg = lookup.itemMap().values().stream().filter(i -> i.getItemType() == ItemType.FG && i.isActive())
                .sorted(Comparator.comparing(Item::getCode)).toList();
        if (fg.isEmpty()) {
            return List.of();
        }
        Map<Long, FormulaVersion> effective = versions.findByStatus(FormulaStatus.EFFECTIVE).stream().collect(Collectors.toMap(FormulaVersion::getFgItemId, v -> v, (a, b) -> a));
        Map<Long, List<ComplianceDoc>> byItem = docs.findByFgItemIdInAndStatusIn(fg.stream().map(Item::getId).toList(), List.of(ComplianceDocStatus.APPROVED, ComplianceDocStatus.PENDING))
                .stream().collect(Collectors.groupingBy(ComplianceDoc::getFgItemId));
        return fg.stream().map(i -> {
            FormulaVersion v = effective.get(i.getId());
            List<ComplianceDoc> mine = byItem.getOrDefault(i.getId(), List.of());
            Long vid = v == null ? null : v.getId();
            return new ProductStatus(i.getId(), i.getCode(), i.getName(), vid, v == null ? null : v.getVersionNo(),
                    state(mine, ComplianceDocType.SAFETY, vid), state(mine, ComplianceDocType.EFFICACY, vid), state(mine, ComplianceDocType.LABEL, vid));
        }).toList();
    }

    private static String state(List<ComplianceDoc> mine, ComplianceDocType type, Long versionId) {
        List<ComplianceDoc> ofType = mine.stream().filter(d -> d.getDocType() == type).toList();
        if (ofType.stream().anyMatch(d -> d.getStatus() == ComplianceDocStatus.APPROVED && d.getFormulaVersionId().equals(versionId))) {
            return "OK";
        }
        if (ofType.stream().anyMatch(d -> d.getStatus() == ComplianceDocStatus.PENDING && d.getFormulaVersionId().equals(versionId))) {
            return "PENDING";
        }
        return ofType.stream().anyMatch(d -> d.getStatus() == ComplianceDocStatus.APPROVED) ? "STALE" : "MISSING";
    }

    /** 这个配方版本还缺什么（放行开关打开时用）。安全评估和标签必须有，功效依据不是每个产品都要。 */
    @Transactional(readOnly = true)
    public List<String> missingFor(Long fgItemId, Long versionId) {
        List<ComplianceDoc> mine = docs.findByFgItemIdInAndStatusIn(List.of(fgItemId), List.of(ComplianceDocStatus.APPROVED, ComplianceDocStatus.PENDING));
        List<String> out = new ArrayList<>();
        for (ComplianceDocType t : List.of(ComplianceDocType.SAFETY, ComplianceDocType.LABEL)) {
            if (!"OK".equals(state(mine, t, versionId))) {
                out.add(t.getLabel());
            }
        }
        return out;
    }

    @Transactional
    public DocView create(CreateReq req) {
        Item fg = lookup.item(req.fgItemId());
        if (fg.getItemType() != ItemType.FG) {
            throw new BizException(fg.getName() + " 不是成品");
        }
        if (req.docDate().isAfter(time.today())) {
            throw new BizException("日期不能晚于今天");
        }
        if (req.docType() == ComplianceDocType.EFFICACY && (req.claim() == null || req.claim().isBlank())) {
            throw new BizException("功效宣称依据要写明宣称内容");
        }
        // 默认对应当前生效版本；配方变更时可以先为草稿或待签批版本做评估
        FormulaVersion v = req.formulaVersionId() != null ? versions.findById(req.formulaVersionId()).filter(x -> x.getFgItemId().equals(fg.getId())).orElseThrow(() -> new BizException("配方版本不属于 " + fg.getName()))
                : versions.findFirstByFgItemIdAndStatus(fg.getId(), FormulaStatus.EFFECTIVE).orElseThrow(() -> new BizException(fg.getName() + " 还没有生效配方，请先录入配方"));
        ComplianceDoc d = new ComplianceDoc();
        d.setDocNo(docNo.next("CD", time.today()));
        d.setFgItemId(fg.getId());
        d.setFormulaVersionId(v.getId());
        d.setDocType(req.docType());
        d.setTitle(req.title().trim());
        d.setClaim(req.claim() == null || req.claim().isBlank() ? null : req.claim().trim());
        d.setEvaluator(req.evaluator().trim());
        d.setDocDate(req.docDate());
        d.setNote(req.note() == null || req.note().isBlank() ? null : req.note().trim());
        d.setStatus(ComplianceDocStatus.PENDING);
        d.setCreatedBy(currentUser.usernameOrSystem());
        d.setCreatedAt(time.now());
        docs.save(d);
        audit.log("登记合规资料", d.getDocNo(), fg.getName() + " V" + v.getVersionNo() + "，" + req.docType().getLabel() + "：" + d.getTitle());
        return views(List.of(d)).get(0);
    }

    @Transactional
    public DocView approve(Long id, ApproveReq req) {
        ComplianceDoc d = docs.lockById(id).orElseThrow(() -> BizException.notFound("合规资料"));
        requireStatus(d, ComplianceDocStatus.PENDING, "签批");
        AppUser signer = signature.verify(req.password());
        if (signer.getUsername().equals(d.getCreatedBy())) {
            throw new BizException("签批人不能是登记人");
        }
        d.setStatus(ComplianceDocStatus.APPROVED);
        d.setSignedBy(signer.getUsername());
        d.setSignedAt(time.now());
        docs.save(d);
        audit.log("签批合规资料", d.getDocNo(), lookup.item(d.getFgItemId()).getName() + "，" + d.getDocType().getLabel() + "：" + d.getTitle());
        return views(List.of(d)).get(0);
    }

    @Transactional
    public DocView reject(Long id, NoteReq req) {
        ComplianceDoc d = docs.lockById(id).orElseThrow(() -> BizException.notFound("合规资料"));
        requireStatus(d, ComplianceDocStatus.PENDING, "退回");
        d.setStatus(ComplianceDocStatus.REJECTED);
        d.setRejectNote(req.note().trim());
        docs.save(d);
        audit.log("退回合规资料", d.getDocNo(), d.getDocType().getLabel() + "：" + d.getTitle(), req.note().trim());
        return views(List.of(d)).get(0);
    }

    /** 已签批的资料出了问题（报告撤回、标签作废）时用。记录留着。 */
    @Transactional
    public DocView voidDoc(Long id, NoteReq req) {
        ComplianceDoc d = docs.lockById(id).orElseThrow(() -> BizException.notFound("合规资料"));
        if (d.getStatus() != ComplianceDocStatus.APPROVED && d.getStatus() != ComplianceDocStatus.PENDING) {
            throw new BizException("资料 " + d.getDocNo() + " 当前为" + d.getStatus().getLabel() + "，不能作废");
        }
        d.setStatus(ComplianceDocStatus.VOID);
        d.setVoidReason(req.note().trim());
        docs.save(d);
        audit.log("作废合规资料", d.getDocNo(), d.getDocType().getLabel() + "：" + d.getTitle(), req.note().trim());
        return views(List.of(d)).get(0);
    }

    /**
     * 全成分表草稿：配方里的原料按含量从高到低，含量不超过 0.1% 的排在后面。
     * 只是草稿：复配原料没有拆开，溶剂等配方外的成分也不在里面，要人工核对后才能上标签。
     */
    @Transactional(readOnly = true)
    public IngredientList ingredients(Long fgItemId) {
        Item fg = lookup.item(fgItemId);
        FormulaVersion v = versions.findFirstByFgItemIdAndStatus(fgItemId, FormulaStatus.EFFECTIVE).orElseThrow(() -> new BizException(fg.getName() + " 还没有生效配方"));
        Map<Long, Item> items = lookup.itemMap();
        List<Ingredient> lines = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        if (fg.getNetG() == null || fg.getNetG().signum() <= 0) {
            problems.add(fg.getName() + " 没有净含量，算不出含量，顺序不可靠");
        }
        for (BomLine l : master.versionLines(v.getId())) {
            Item m = items.get(l.getItemId());
            if (m.getItemType() != ItemType.RM) {
                continue;
            }
            BigDecimal grams = FormulaCompliance.toGrams(l.getQtyPer(), m.getUnit());
            BigDecimal pct = grams == null || fg.getNetG() == null || fg.getNetG().signum() <= 0 ? null : grams.multiply(BigDecimal.valueOf(100)).divide(fg.getNetG(), 4, RoundingMode.HALF_UP);
            if (pct == null && grams == null) {
                problems.add(m.getName() + " 的单位不是质量单位，算不出含量");
            }
            if (m.getInci() == null || m.getInci().isBlank()) {
                problems.add(m.getName() + " 没填 INCI 名称");
            }
            lines.add(new Ingredient(m.getCode(), m.getName(), m.getInci(), pct));
        }
        lines.sort(Comparator.comparing(Ingredient::pct, Comparator.nullsLast(Comparator.reverseOrder())));
        BigDecimal total = lines.stream().map(Ingredient::pct).filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(BigDecimal.valueOf(100)) < 0) {
            problems.add("配方里的原料合计 " + total.stripTrailingZeros().toPlainString() + "%，其余（如水）没有录在配方里，不在这份清单中");
        }
        problems.add("复配原料没有拆成单一成分，请核对");
        String text = lines.stream().map(i -> i.pct() != null && i.pct().compareTo(LABEL_ORDER_LINE) <= 0 ? "（≤0.1%）" + i.itemName() : i.itemName()).collect(Collectors.joining("、"));
        return new IngredientList(fgItemId, fg.getName(), v.getVersionNo(), lines, text, problems);
    }

    private static void requireStatus(ComplianceDoc d, ComplianceDocStatus need, String action) {
        if (d.getStatus() != need) {
            throw new BizException("资料 " + d.getDocNo() + " 当前为" + d.getStatus().getLabel() + "，不能" + action);
        }
    }

    private List<DocView> views(List<ComplianceDoc> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, Item> items = lookup.itemMap();
        Map<Long, Integer> versionNos = new HashMap<>();
        versions.findAllById(list.stream().map(ComplianceDoc::getFormulaVersionId).distinct().toList()).forEach(v -> versionNos.put(v.getId(), v.getVersionNo()));
        return list.stream().map(d -> {
            Item i = items.get(d.getFgItemId());
            return new DocView(d.getId(), d.getDocNo(), d.getFgItemId(), i.getCode(), i.getName(), d.getFormulaVersionId(), versionNos.get(d.getFormulaVersionId()), d.getDocType(), d.getDocType().getLabel(),
                    d.getTitle(), d.getClaim(), d.getEvaluator(), d.getDocDate(), d.getNote(), d.getStatus(), d.getStatus().getLabel(), d.getRejectNote(), d.getVoidReason(), d.getSignedBy(), d.getSignedAt(), d.getCreatedBy(), d.getCreatedAt());
        }).toList();
    }
}
