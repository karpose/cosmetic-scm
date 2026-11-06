package com.cosmetic.scm.master;

import com.cosmetic.scm.master.MasterDtos.BomReq;
import com.cosmetic.scm.master.MasterDtos.BomLineReq;
import com.cosmetic.scm.domain.SupplyMode;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import java.util.HashMap;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.master.MasterDtos.ItemReq;
import com.cosmetic.scm.master.MasterDtos.PartnerReq;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * 主数据批量导入（前端把 Excel / CSV 解析成行后提交）。
 * 编码已存在的行按修改处理，不存在的新增；每一行都走与手工录入相同的校验和审计。
 * 任何一行出错、或只是预检（dryRun），整个事务回滚，不会出现导入一半的情况。
 */
@Service
@RequiredArgsConstructor
public class MasterImportService {

    /** 表头占第 1 行，数据从第 2 行开始，报错时按 Excel 行号提示。 */
    private static final int FIRST_DATA_LINE = 2;

    private static final Map<String, String> FIELD_LABELS = Map.ofEntries(
            Map.entry("code", "编码"), Map.entry("name", "名称"), Map.entry("itemType", "类型"), Map.entry("unit", "单位"),
            Map.entry("inci", "INCI 名称"), Map.entry("limitPct", "限用浓度上限"), Map.entry("shelfMonths", "保质期（月）"),
            Map.entry("filingNo", "备案编号"), Map.entry("netG", "净含量"), Map.entry("kind", "类别"),
            Map.entry("licenseNo", "资质编号"), Map.entry("licenseExpiry", "资质有效期"), Map.entry("licenseScope", "许可范围"),
            Map.entry("batchPrefix", "批号前缀"), Map.entry("channel", "渠道"), Map.entry("minShelfPct", "最低剩余效期"),
            Map.entry("contact", "联系人"), Map.entry("address", "地址"), Map.entry("stdCost", "标准成本"),
            Map.entry("listPrice", "指导价"));

    private final MasterService master;
    private final ItemRepository items;
    private final PartnerRepository partners;
    private final Validator validator;
    private final AuditService audit;

    public record ItemImportReq(@NotEmpty(message = "没有可导入的行") @Size(max = 2000, message = "一次最多导入 2000 行，请分成几个文件") List<ItemReq> rows, boolean dryRun) {
    }

    public record PartnerImportReq(@NotEmpty(message = "没有可导入的行") @Size(max = 2000, message = "一次最多导入 2000 行，请分成几个文件") List<PartnerReq> rows,
                                   boolean dryRun) {
    }

    /** 配方的一行。同一个成品写多行，一起作为它的首版配方。 */
    public record BomImportRow(@NotBlank(message = "请填写成品编码") String fgCode,
                               @NotBlank(message = "请填写物料编码") String itemCode,
                               @NotNull(message = "请填写单耗") @Positive(message = "单耗要大于 0") @jakarta.validation.constraints.Digits(integer = 12, fraction = 6, message = "单耗最多 12 位整数、6 位小数") BigDecimal qtyPer,
                               @NotNull(message = "请填写供料方式") SupplyMode supplyMode) {
    }

    public record BomImportReq(@NotEmpty(message = "没有可导入的行") @Size(max = 20000, message = "一次最多导入 20000 行，请分成几个文件") List<BomImportRow> rows, boolean dryRun) {
    }

    public record RowError(int line, String code, String message) {
    }

    public record ImportResult(int total, int created, int updated, boolean applied, List<RowError> errors) {
    }

    @Transactional
    public ImportResult importItems(ItemImportReq req) {
        Set<String> existing = items.findAll().stream().map(Item::getCode).collect(Collectors.toSet());
        Function<String, Long> idOf = code -> items.findAll().stream().filter(i -> i.getCode().equals(code))
                .findFirst().map(Item::getId).orElseThrow();
        return run("物料", req.rows(), req.dryRun(), ItemReq::code, existing,
                r -> master.createItem(r), r -> master.updateItem(idOf.apply(r.code().trim()), r));
    }

    @Transactional
    public ImportResult importPartners(PartnerImportReq req) {
        Set<String> existing = partners.findAll().stream().map(Partner::getCode).collect(Collectors.toSet());
        return run("往来单位", req.rows(), req.dryRun(), PartnerReq::code, existing,
                r -> master.createPartner(r),
                r -> master.updatePartner(partners.findByCode(r.code().trim()).orElseThrow().getId(), r));
    }

    /**
     * 批量录入首版配方：SKU 多的时候不可能在弹窗里一个个录。全成或全不成，
     * 走和界面相同的校验（禁用、限用浓度、重复物料）；已有生效配方的成品要走配方变更，这里拒绝。
     */
    @Transactional
    public ImportResult importBoms(BomImportReq req) {
        Map<String, Item> byCode = items.findAll().stream().collect(Collectors.toMap(Item::getCode, Function.identity()));
        List<RowError> errors = new ArrayList<>();
        Map<String, List<BomLineReq>> byFg = new java.util.LinkedHashMap<>();
        Map<String, Integer> firstLine = new HashMap<>();
        for (int i = 0; i < req.rows().size(); i++) {
            BomImportRow r = req.rows().get(i);
            int line = i + FIRST_DATA_LINE;
            String fg = r.fgCode() == null ? null : r.fgCode().trim();
            Set<ConstraintViolation<BomImportRow>> violations = validator.validate(r);
            if (!violations.isEmpty()) {
                errors.add(new RowError(line, fg, violations.stream().map(ConstraintViolation::getMessage).sorted().collect(Collectors.joining("；"))));
                continue;
            }
            Item fgItem = byCode.get(fg);
            Item material = byCode.get(r.itemCode().trim());
            if (fgItem == null) {
                errors.add(new RowError(line, fg, "成品编码不存在，请先导入物料"));
            } else if (material == null) {
                errors.add(new RowError(line, fg, "物料编码 " + r.itemCode().trim() + " 不存在"));
            } else {
                firstLine.putIfAbsent(fg, line);
                byFg.computeIfAbsent(fg, k -> new ArrayList<>()).add(new BomLineReq(material.getId(), r.qtyPer(), r.supplyMode()));
            }
        }
        int created = 0;
        for (Map.Entry<String, List<BomLineReq>> e : byFg.entrySet()) {
            try {
                master.saveBom(byCode.get(e.getKey()).getId(), new BomReq(e.getValue()));
                created++;
            } catch (BizException ex) {
                errors.add(new RowError(firstLine.get(e.getKey()), e.getKey(), ex.getMessage()));
            }
        }
        errors.sort(java.util.Comparator.comparingInt(RowError::line));
        boolean apply = errors.isEmpty() && !req.dryRun();
        if (apply) {
            audit.log("批量导入配方", created + " 个成品", req.rows().size() + " 行");
        } else {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
        return new ImportResult(req.rows().size(), created, 0, apply, errors);
    }

    private <T> ImportResult run(String what, List<T> rows, boolean dryRun, Function<T, String> codeOf, Set<String> existing,
                                 Function<T, ?> create, Function<T, ?> update) {
        List<RowError> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int created = 0;
        int updated = 0;
        for (int i = 0; i < rows.size(); i++) {
            T row = rows.get(i);
            int line = i + FIRST_DATA_LINE;
            String code = codeOf.apply(row) == null ? null : codeOf.apply(row).trim();
            Set<ConstraintViolation<T>> violations = validator.validate(row);
            if (!violations.isEmpty()) {
                errors.add(new RowError(line, code, violations.stream()
                        .map(v -> FIELD_LABELS.getOrDefault(v.getPropertyPath().toString(), v.getPropertyPath().toString())
                                + " " + v.getMessage()).sorted().collect(Collectors.joining("；"))));
                continue;
            }
            if (!seen.add(code)) {
                errors.add(new RowError(line, code, "编码在文件里重复"));
                continue;
            }
            try {
                if (existing.contains(code)) {
                    update.apply(row);
                    updated++;
                } else {
                    create.apply(row);
                    created++;
                }
            } catch (BizException e) {
                errors.add(new RowError(line, code, e.getMessage()));
            }
        }
        boolean apply = errors.isEmpty() && !dryRun;
        if (apply) {
            audit.log("批量导入" + what, rows.size() + " 行", "新增 " + created + "，修改 " + updated);
        } else {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
        return new ImportResult(rows.size(), created, updated, apply, errors);
    }
}
