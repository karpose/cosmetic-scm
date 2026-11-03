package com.cosmetic.scm.master;

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
            Map.entry("contact", "联系人"), Map.entry("address", "地址"));

    private final MasterService master;
    private final ItemRepository items;
    private final PartnerRepository partners;
    private final Validator validator;
    private final AuditService audit;

    public record ItemImportReq(@NotEmpty(message = "没有可导入的行") @Size(max = 2000) List<ItemReq> rows, boolean dryRun) {
    }

    public record PartnerImportReq(@NotEmpty(message = "没有可导入的行") @Size(max = 2000) List<PartnerReq> rows,
                                   boolean dryRun) {
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
