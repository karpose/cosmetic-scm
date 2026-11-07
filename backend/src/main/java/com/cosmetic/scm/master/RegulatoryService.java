package com.cosmetic.scm.master;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.RegulatedCatalog;
import com.cosmetic.scm.domain.rules.FormulaCompliance;
import com.cosmetic.scm.master.MasterImportService.RowError;
import com.cosmetic.scm.security.CurrentUser;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 原料法规目录：导入后，配方校验、下单和询价都按它判断，比物料上人工勾选的更严就按它。
 * 系统不内置法规数据，内容要按官方发布的版本导入，来源必填。
 * 只按 INCI 对照：物料没填 INCI 就对不上，会列在对照结果里。
 */
@Service
@RequiredArgsConstructor
public class RegulatoryService {

    private final RegulatedSubstanceRepository substances;
    private final ItemRepository items;
    private final BomRepository boms;
    private final BomLineRepository bomLines;
    private final Validator validator;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    private static final int AUDIT_PART_CHARS = 950;

    public record Rule(RegulatedCatalog catalog, BigDecimal maxPct, String source) {
    }

    public record RowReq(@NotNull(message = "请填写类别") RegulatedCatalog catalog,
                         @NotBlank(message = "请填写 INCI 名称") @Size(max = 200) String inciName,
                         @Size(max = 200) String cnName, @Size(max = 40) String casNo,
                         @DecimalMin(value = "0", message = "上限不能为负") @DecimalMax(value = "100", message = "上限不能超过 100") @Digits(integer = 5, fraction = 4, message = "上限最多 4 位小数") BigDecimal maxPct,
                         @Size(max = 500) String conditions,
                         @NotBlank(message = "请填写来源文件和版本") @Size(max = 200) String source) {
    }

    public record Change(String code, String field, String from, String to) {
    }

    public record RegImportResult(int total, int created, int updated, boolean applied, List<RowError> errors, List<Change> changes) {
    }

    public record ImportReq(@NotEmpty(message = "没有可导入的行") @Size(max = 5000, message = "一次最多导入 5000 行，请分成几个文件") List<RowReq> rows, boolean dryRun) {
    }

    public record RetireReq(@NotBlank(message = "请写明停用原因") @Size(max = 300) String reason) {
    }

    public record SubstanceView(Long id, RegulatedCatalog catalog, String catalogLabel, String inciName, String cnName, String casNo, BigDecimal maxPct,
                                String conditions, String source, boolean active, String updatedBy, LocalDateTime updatedAt) {
    }

    /** state：BANNED_NOT_MARKED 目录禁用，物料没标；LIMIT_LOOSER 物料的上限比目录松或没填；NO_INCI 没填 INCI，对不上。 */
    public record ItemGap(Long itemId, String code, String name, String inci, String state, String detail) {
    }

    /** 生效配方里，按当前目录已经不合规的物料。 */
    public record Violation(Long fgItemId, String fgCode, String fgName, String itemCode, String message) {
    }

    public record Comparison(long activeRules, List<ItemGap> gaps, List<Violation> violations) {
    }

    /**
     * 和物料对照用的键：不分大小写、不分重音、去首尾空格。
     * MySQL 的 utf8mb4_0900_ai_ci 认为 é 和 e 相同，这里不折叠的话，Café 和 Cafe 在文件里不算重复，到库里却是同一行，
     * 禁用条目会被悄悄改掉。H2 区分重音，测不出来。
     */
    public static String key(String inci) {
        if (inci == null) {
            return "";
        }
        return Normalizer.normalize(inci.trim(), Normalizer.Form.NFKD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    /** 物料适用的目录规则。只有原料才有。 */
    public Optional<Rule> ruleOf(Item item) {
        if (item.getItemType() != ItemType.RM || item.getInci() == null || item.getInci().isBlank()) {
            return Optional.empty();
        }
        return substances.findByInciKeyAndActiveTrue(key(item.getInci())).map(s -> new Rule(s.getCatalog(), s.getMaxPct(), s.getSource()));
    }

    /** 物料上标了禁用，或目录里是禁用。 */
    public boolean isBanned(Item item) {
        return item.isBanned() || ruleOf(item).filter(r -> r.catalog() == RegulatedCatalog.BANNED).isPresent();
    }

    /** 物料上的上限和目录的上限取严的。 */
    public BigDecimal effectiveLimit(Item item) {
        BigDecimal mine = item.getLimitPct();
        BigDecimal rule = ruleOf(item).map(Rule::maxPct).orElse(null);
        if (mine == null) {
            return rule;
        }
        return rule == null || mine.compareTo(rule) <= 0 ? mine : rule;
    }

    @Transactional(readOnly = true)
    public List<SubstanceView> list() {
        return substances.findAllByOrderByCatalogAscInciNameAsc().stream().map(RegulatoryService::view).toList();
    }

    /** 全成或全不成。已存在的 INCI 按修改处理，审计记下改了什么。 */
    /** 先校验、算出变化，全部通过才写。一行一查会让大目录慢到超时：实测 5000 行 49 秒，预检也一样。 */
    @Transactional
    public RegImportResult importRows(ImportReq req) {
        List<RowError> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int created = 0;
        int updated = 0;
        List<Change> changes = new ArrayList<>();
        Set<String> sources = new java.util.LinkedHashSet<>();
        Map<String, RegulatedSubstance> existing = substances.findByInciKeyIn(req.rows().stream().map(r -> key(r.inciName())).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(RegulatedSubstance::getInciKey, s -> s));
        for (int i = 0; i < req.rows().size(); i++) {
            RowReq r = req.rows().get(i);
            int line = i + 2;
            String inci = r.inciName() == null ? null : r.inciName().trim();
            Set<ConstraintViolation<RowReq>> bad = validator.validate(r);
            if (!bad.isEmpty()) {
                errors.add(new RowError(line, inci, bad.stream().map(ConstraintViolation::getMessage).sorted().collect(Collectors.joining("；"))));
                continue;
            }
            if (!seen.add(key(inci))) {
                errors.add(new RowError(line, inci, "INCI 在文件里重复"));
                continue;
            }
            if (r.catalog() == RegulatedCatalog.BANNED && r.maxPct() != null) {
                errors.add(new RowError(line, inci, "禁用的不能填浓度上限"));
                continue;
            }
            RegulatedSubstance old = existing.get(key(inci));
            if (old == null) {
                created++;
            } else {
                updated++;
                changes.addAll(changesOf(old, inci, r));
            }
            sources.add(r.source().trim());
        }
        boolean apply = errors.isEmpty() && !req.dryRun();
        if (apply) {
            // 操作人只查一次：每查一次库 Hibernate 都要检查一遍所有已载入的条目，逐行查就是平方级
            String by = currentUser.usernameOrSystem();
            LocalDateTime now = time.now();
            for (RowReq r : req.rows()) {
                write(existing.getOrDefault(key(r.inciName()), new RegulatedSubstance()), r, by, now);
            }
            audit.log("导入法规目录", req.rows().size() + " 条", "新增 " + created + "，修改 " + updated + "，规则变化 " + changes.size() + " 处；来源 " + String.join("、", sources));
            logChanges(changes);
        }
        return new RegImportResult(req.rows().size(), created, updated, apply, errors, changes);
    }

    /**
     * 审计详情最长 1000 字，合在一条里会被截断，查不出改了什么，所以分段写。
     * 也不能一条变化写一行：写审计要查操作人，5000 条全变时要 65 秒，超过 nginx 的 60 秒，用户看到 504 而事务还在跑。
     */
    private void logChanges(List<Change> changes) {
        StringBuilder part = new StringBuilder();
        for (Change c : changes) {
            String one = c.code() + "：" + c.field() + " " + c.from() + " 改为 " + c.to();
            if (part.length() + one.length() + 1 > AUDIT_PART_CHARS) {
                audit.log("修改法规条目", "法规目录", part.toString());
                part.setLength(0);
            }
            part.append(part.isEmpty() ? "" : "；").append(one);
        }
        if (!part.isEmpty()) {
            audit.log("修改法规条目", "法规目录", part.toString());
        }
    }

    /** 只列影响校验的变化。来源每个版本都不同，列出来反而看不到真正的变化。 */
    private List<Change> changesOf(RegulatedSubstance old, String inci, RowReq r) {
        List<Change> list = new ArrayList<>();
        if (old.getCatalog() != r.catalog()) {
            list.add(new Change(inci, "类别", old.getCatalog().getLabel(), r.catalog().getLabel()));
        }
        if (!sameNumber(old.getMaxPct(), r.maxPct())) {
            list.add(new Change(inci, "浓度上限", pctOrNone(old.getMaxPct()), pctOrNone(r.maxPct())));
        }
        if (!old.isActive()) {
            list.add(new Change(inci, "状态", "已停用", "生效"));
        }
        return list;
    }

    private void write(RegulatedSubstance s, RowReq r, String by, LocalDateTime now) {
        String inci = r.inciName().trim();
        s.setCatalog(r.catalog());
        s.setInciName(inci);
        s.setInciKey(key(inci));
        s.setCnName(blankToNull(r.cnName()));
        s.setCasNo(blankToNull(r.casNo()));
        s.setMaxPct(r.maxPct());
        s.setConditions(blankToNull(r.conditions()));
        s.setSource(r.source().trim());
        s.setActive(true);
        s.setUpdatedBy(by);
        s.setUpdatedAt(now);
        substances.save(s);
    }

    /** 法规目录只增不删，不再适用的停用，留下记录。 */
    @Transactional
    public SubstanceView retire(Long id, RetireReq req) {
        RegulatedSubstance s = substances.findById(id).orElseThrow(() -> BizException.notFound("目录条目"));
        if (!s.isActive()) {
            throw new BizException(s.getInciName() + " 已经停用");
        }
        s.setActive(false);
        s.setUpdatedBy(currentUser.usernameOrSystem());
        s.setUpdatedAt(time.now());
        substances.save(s);
        audit.log("停用法规目录条目", s.getInciName(), s.getCatalog().getLabel() + plainPct(s.getMaxPct()), req.reason().trim());
        return view(s);
    }

    @Transactional(readOnly = true)
    public Comparison compare() {
        Map<String, RegulatedSubstance> rules = substances.findByActiveTrue().stream().collect(Collectors.toMap(RegulatedSubstance::getInciKey, s -> s));
        List<ItemGap> gaps = new ArrayList<>();
        for (Item i : items.findAll()) {
            if (i.getItemType() != ItemType.RM || !i.isActive()) {
                continue;
            }
            if (i.getInci() == null || i.getInci().isBlank()) {
                gaps.add(new ItemGap(i.getId(), i.getCode(), i.getName(), null, "NO_INCI", "没填 INCI，法规目录对不上，不会被校验"));
                continue;
            }
            RegulatedSubstance s = rules.get(key(i.getInci()));
            if (s == null) {
                continue;
            }
            if (s.getCatalog() == RegulatedCatalog.BANNED && !i.isBanned()) {
                gaps.add(new ItemGap(i.getId(), i.getCode(), i.getName(), i.getInci(), "BANNED_NOT_MARKED", "目录里是禁用，物料上没标。系统已按禁用处理，请把物料也改过来"));
            } else if (s.getMaxPct() != null && (i.getLimitPct() == null || i.getLimitPct().compareTo(s.getMaxPct()) > 0)) {
                gaps.add(new ItemGap(i.getId(), i.getCode(), i.getName(), i.getInci(), "LIMIT_LOOSER",
                        "目录上限 " + plain(s.getMaxPct()) + "%，物料上是 " + (i.getLimitPct() == null ? "没填" : plain(i.getLimitPct()) + "%") + "。系统已按目录校验"));
            }
        }
        return new Comparison(rules.size(), gaps, violations());
    }

    /**
     * 按当前目录，生效配方里已经不合规的物料。法规一变，已上市的产品就可能不合规，要有人看到。
     * 只查和目录有关的配方行，不扫全表。
     */
    @Transactional(readOnly = true)
    public List<Violation> violations() {
        List<Violation> out = new ArrayList<>();
        for (Object[] row : bomLines.findLinesOfRegulatedItems()) {
            BomLine line = (BomLine) row[0];
            Item m = (Item) row[1];
            RegulatedSubstance s = (RegulatedSubstance) row[2];
            Bom bom = boms.findById(line.getBomId()).orElse(null);
            Item fg = bom == null ? null : items.findById(bom.getFgItemId()).orElse(null);
            if (fg == null || !fg.isActive()) {
                continue;
            }
            FormulaCompliance.Check c = FormulaCompliance.check(m.getName(), true, s.getCatalog() == RegulatedCatalog.BANNED, s.getMaxPct(), line.getQtyPer(), m.getUnit(), fg.getNetG());
            if (c.blocking()) {
                out.add(new Violation(fg.getId(), fg.getCode(), fg.getName(), m.getCode(), c.message()));
            }
        }
        return out;
    }

    private static SubstanceView view(RegulatedSubstance s) {
        return new SubstanceView(s.getId(), s.getCatalog(), s.getCatalog().getLabel(), s.getInciName(), s.getCnName(), s.getCasNo(), s.getMaxPct(), s.getConditions(),
                s.getSource(), s.isActive(), s.getUpdatedBy(), s.getUpdatedAt());
    }

    private static boolean sameNumber(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static String plainPct(BigDecimal v) {
        return v == null ? "" : " " + plain(v) + "%";
    }

    private static String pctOrNone(BigDecimal v) {
        return v == null ? "无上限" : plain(v) + "%";
    }
}
