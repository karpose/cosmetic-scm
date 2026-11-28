package com.cosmetic.scm.inventory;

import com.cosmetic.scm.common.Qty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.Decision;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.QcStage;
import com.cosmetic.scm.domain.RetainStatus;
import com.cosmetic.scm.domain.SourceType;
import com.cosmetic.scm.domain.TxnType;
import com.cosmetic.scm.domain.WarehouseKind;
import com.cosmetic.scm.domain.rules.ShelfLife;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.ItemRepository;
import com.cosmetic.scm.master.MasterImportService.ImportResult;
import com.cosmetic.scm.master.MasterImportService.RowError;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.PartnerRepository;
import com.cosmetic.scm.master.Warehouse;
import com.cosmetic.scm.master.WarehouseRepository;
import com.cosmetic.scm.qms.RetainSample;
import com.cosmetic.scm.qms.RetainSampleRepository;
import com.cosmetic.scm.quality.QualityDecision;
import com.cosmetic.scm.quality.QualityDecisionRepository;
import com.cosmetic.scm.security.AppUser;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 期初库存导入：上线时把仓库里现有的批次录入系统。不走采购和生产单，避免虚构单据污染追溯和看板。
 * 全成或全不成。同一批号可以有多行（分布在不同仓库）。已放行的批次相当于一次放行决定，
 * 所以只有质量安全负责人能导入，并且要再次输入密码：写入放行记录、库存流水和审计，成品同时入留样台账。
 * 这些批次没有上游谱系，追溯到“期初导入”为止，上线前的来源要查原有记录。
 */
@Service
@RequiredArgsConstructor
public class OpeningStockService {

    private static final int FIRST_DATA_LINE = 2;

    private final ItemRepository items;
    private final WarehouseRepository warehouses;
    private final PartnerRepository partners;
    private final BatchRepository batches;
    private final InventoryService inventory;
    private final QualityDecisionRepository decisions;
    private final RetainSampleRepository retains;
    private final com.cosmetic.scm.qms.RetainSiteRepository retainSites;
    private final MasterLookup lookup;
    private final DocNoService docNo;
    private final Validator validator;
    private final AuditService audit;
    private final TimeProvider time;

    /** 行不加 @Valid：缺字段要在 run 里按 Excel 行号报出来，而不是整个请求被拒绝。status：RELEASED 已放行 / QUARANTINE 待检（物料）/ PENDING_RELEASE 待上市放行（成品）。 */
    public record OpeningRow(@NotBlank(message = "请填写物料编码") @Size(max = 40) String itemCode,
                             @NotBlank(message = "请填写批号") @Size(max = 40) String batchNo,
                             @Size(max = 60) String extBatchNo,
                             @NotNull(message = "请填写生产日期") LocalDate mfgDate,
                             LocalDate expDate,
                             @NotBlank(message = "请填写仓库编码") @Size(max = 40) String warehouseCode,
                             @NotNull(message = "请填写数量") @Positive(message = "数量要大于 0") @Qty BigDecimal qty,
                             @NotNull(message = "请填写状态") BatchStatus status,
                             @Size(max = 40) String partnerCode,
                             @Size(max = 60) String reportNo,
                             @Min(value = 1, message = "留样数量要大于 0") Integer retainQty,
                             @Size(max = 100) String retainLocation) {
    }

    public record OpeningReq(@NotEmpty(message = "没有可导入的行") @Size(max = 5000, message = "一次最多导入 5000 行，请分成几个文件") List<OpeningRow> rows, boolean dryRun, String password) {
    }

    /** 同一批号的多行合并后的结果。 */
    private static final class Plan {
        OpeningRow first;
        Item item;
        LocalDate exp;
        Long partnerId;
        BigDecimal total = BigDecimal.ZERO;
        final Map<Long, BigDecimal> stock = new LinkedHashMap<>();
    }

    /** 预检不要求签批，signer 可以为空；真正导入时由 Controller 验过密码再传入。 */
    @Transactional
    public ImportResult run(OpeningReq req, AppUser signer) {
        Map<String, Item> im = items.findAll().stream().collect(Collectors.toMap(Item::getCode, i -> i));
        Map<String, Warehouse> wm = warehouses.findAll().stream().collect(Collectors.toMap(Warehouse::getCode, w -> w));
        Map<String, Partner> pm = partners.findAll().stream().collect(Collectors.toMap(Partner::getCode, p -> p));
        Set<String> existing = new HashSet<>();
        List<String> nos = req.rows().stream().map(OpeningRow::batchNo).filter(java.util.Objects::nonNull)
                .map(String::trim).filter(n -> !n.isEmpty()).distinct().toList();
        for (int i = 0; i < nos.size(); i += 1000) {
            existing.addAll(batches.findExistingBatchNos(nos.subList(i, Math.min(nos.size(), i + 1000))));
        }

        List<RowError> errors = new ArrayList<>();
        Map<String, Plan> plans = new LinkedHashMap<>();
        for (int i = 0; i < req.rows().size(); i++) {
            OpeningRow r = req.rows().get(i);
            int line = i + FIRST_DATA_LINE;
            String no = r.batchNo() == null ? null : r.batchNo().trim();
            Set<ConstraintViolation<OpeningRow>> violations = validator.validate(r);
            if (!violations.isEmpty()) {
                errors.add(new RowError(line, no, violations.stream().map(ConstraintViolation::getMessage).sorted().collect(Collectors.joining("；"))));
                continue;
            }
            String problem = check(r, no, im, wm, pm, existing, plans);
            if (problem != null) {
                errors.add(new RowError(line, no, problem));
            }
        }
        boolean apply = errors.isEmpty() && !req.dryRun();
        if (apply) {
            apply(plans, signer);
        }
        return new ImportResult(req.rows().size(), plans.size(), 0, apply, errors);
    }

    /** 校验一行并并入计划；有问题返回原因。 */
    private String check(OpeningRow r, String no, Map<String, Item> im, Map<String, Warehouse> wm, Map<String, Partner> pm,
                         Set<String> existing, Map<String, Plan> plans) {
        LocalDate today = time.today();
        Item item = im.get(r.itemCode().trim());
        if (item == null) {
            return "物料编码 " + r.itemCode().trim() + " 不存在，请先导入主数据";
        }
        if (existing.contains(no)) {
            return "批号已存在于系统中";
        }
        Warehouse wh = wm.get(r.warehouseCode().trim());
        if (wh == null) {
            return "仓库编码 " + r.warehouseCode().trim() + " 不存在";
        }
        if (wh.getKind() == WarehouseKind.RETURN) {
            return "退货隔离区的货不能走期初导入，请按退货处理";
        }
        boolean fg = item.getItemType() == ItemType.FG;
        BatchStatus st = r.status();
        if (st != BatchStatus.RELEASED && st != (fg ? BatchStatus.PENDING_RELEASE : BatchStatus.QUARANTINE)) {
            return (fg ? "成品" : "物料") + "的期初状态只能是已放行或" + (fg ? "待上市放行" : "待检");
        }
        if (r.mfgDate().isAfter(today)) {
            return "生产日期不能晚于今天";
        }
        LocalDate exp = r.expDate() != null ? r.expDate() : ShelfLife.expiryFrom(r.mfgDate(), item.getShelfMonths());
        if (exp == null) {
            return item.getName() + " 没有维护保质期，请填写有效期";
        }
        if (!exp.isAfter(r.mfgDate())) {
            return "有效期要晚于生产日期";
        }
        if (st == BatchStatus.RELEASED) {
            if (ShelfLife.isExpired(exp, today)) {
                return "已过有效期（" + exp + "），不能按已放行导入；过期的货请先在线下报废，不要进系统";
            }
            if (r.reportNo() == null || r.reportNo().isBlank()) {
                return "已放行的批次请填写检验报告编号";
            }
            if (fg) {
                if (item.getFilingNo() == null) {
                    return item.getName() + " 没有备案编号，不能按已放行导入";
                }
                if (r.retainQty() == null || r.retainLocation() == null || r.retainLocation().isBlank()) {
                    return "已放行的成品请填写留样数量和留样地点（同一批号填在任意一行）";
                }
            }
        }
        Long partnerId = null;
        if (r.partnerCode() != null && !r.partnerCode().isBlank()) {
            Partner p = pm.get(r.partnerCode().trim());
            if (p == null) {
                return "往来单位编码 " + r.partnerCode().trim() + " 不存在";
            }
            partnerId = p.getId();
        }
        Plan plan = plans.get(no);
        if (plan == null) {
            plan = new Plan();
            plan.first = r;
            plan.item = item;
            plan.exp = exp;
            plan.partnerId = partnerId;
            plans.put(no, plan);
        } else {
            OpeningRow f = plan.first;
            boolean same = plan.item.getId().equals(item.getId()) && f.status() == st && f.mfgDate().equals(r.mfgDate())
                    && plan.exp.equals(exp) && Objects.equals(plan.partnerId, partnerId);
            if (!same) {
                return "同一批号的物料、状态、日期或来源单位和前面的行不一致";
            }
            if (plan.stock.containsKey(wh.getId())) {
                return "同一批号在 " + wh.getName() + " 重复，请合并成一行";
            }
        }
        plan.stock.put(wh.getId(), r.qty());
        plan.total = plan.total.add(r.qty());
        return null;
    }

    private void apply(Map<String, Plan> plans, AppUser signer) {
        LocalDate today = time.today();
        String docNoValue = docNo.next("OB", today);
        // 只登记了一个在用的留样地点时，期初的留样记到这个地点；有多个或没有的留空，之后在留样台账里指定
        boolean anyFgRelease = plans.values().stream().anyMatch(p -> p.first.status() == BatchStatus.RELEASED && p.item.getItemType() == ItemType.FG);
        com.cosmetic.scm.qms.RetainSite site = anyFgRelease ? com.cosmetic.scm.qms.RetainSites.defaultOrNull(retainSites) : null;
        Map<BatchStatus, Integer> counts = new HashMap<>();
        for (Map.Entry<String, Plan> e : plans.entrySet()) {
            Plan p = e.getValue();
            OpeningRow r = p.first;
            Batch b = inventory.createBatch(e.getKey(), p.item, r.mfgDate(), p.exp, r.status(), SourceType.OPEN, docNoValue, p.partnerId,
                    r.extBatchNo() == null || r.extBatchNo().isBlank() ? null : r.extBatchNo().trim(), p.total);
            for (Map.Entry<Long, BigDecimal> s : p.stock.entrySet()) {
                inventory.change(b, s.getKey(), s.getValue(), TxnType.OPENING, docNoValue);
            }
            if (r.status() == BatchStatus.RELEASED) {
                recordRelease(b, p, r, signer, site);
            }
            counts.merge(r.status(), 1, Integer::sum);
        }
        audit.log("期初库存导入", docNoValue, plans.size() + " 个批次：" + counts.entrySet().stream()
                .map(c -> c.getKey().getLabel() + " " + c.getValue()).collect(Collectors.joining("，")) + "，签批人 " + signer.getDisplayName());
    }

    private void recordRelease(Batch b, Plan p, OpeningRow r, AppUser signer, com.cosmetic.scm.qms.RetainSite site) {
        boolean fg = p.item.getItemType() == ItemType.FG;
        QualityDecision d = new QualityDecision();
        d.setBatchId(b.getId());
        d.setStage(fg ? QcStage.MARKET_RELEASE : QcStage.MATERIAL_QC);
        d.setDecision(Decision.RELEASED);
        d.setReportNo(r.reportNo().trim());
        d.setNote("期初导入：上线前已放行，依据原有记录");
        d.setSignedById(signer.getId());
        d.setSignedByName(signer.getDisplayName());
        d.setSignedAt(time.now());
        if (fg) {
            d.setChecklist("期初导入，放行依据见原有记录");
            d.setRetainQty(r.retainQty());
            d.setRetainLocation(r.retainLocation().trim());
            d.setRetainUntil(ShelfLife.retainUntil(p.exp));
            RetainSample s = new RetainSample();
            s.setBatchId(b.getId());
            s.setQty(r.retainQty());
            s.setRemainingQty(r.retainQty());
            s.setLocation(r.retainLocation().trim());
            s.setSiteId(site == null ? null : site.getId());
            s.setRetainUntil(d.getRetainUntil());
            s.setStatus(RetainStatus.HELD);
            s.setCreatedBy(signer.getDisplayName());
            s.setCreatedAt(time.now());
            retains.save(s);
        }
        decisions.save(d);
    }
}
