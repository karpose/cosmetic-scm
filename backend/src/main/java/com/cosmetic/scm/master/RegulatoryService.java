package com.cosmetic.scm.master;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
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
    private final RegulatedAliasRepository aliasRepo;
    private final RegulatedCasRepository casRepo;
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
                         @NotBlank(message = "请填写来源文件和版本") @Size(max = 200) String source,
                         @Size(max = 1000, message = "别名太长，最多 1000 字") String aliases) {
        public RowReq(RegulatedCatalog catalog, String inciName, String cnName, String casNo, BigDecimal maxPct, String conditions, String source) {
            this(catalog, inciName, cnName, casNo, maxPct, conditions, source, null);
        }
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
                                String conditions, String source, boolean active, String updatedBy, LocalDateTime updatedAt, String aliases) {
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

    /** 物料适用的目录规则。只有原料才有。按 INCI、别名、CAS 号都能对上；对上多条时取最严的。 */
    public Optional<Rule> ruleOf(Item item) {
        if (item.getItemType() != ItemType.RM) {
            return Optional.empty();
        }
        List<RegulatedSubstance> found = new ArrayList<>();
        if (item.getInci() != null && !item.getInci().isBlank()) {
            String k = key(item.getInci());
            substances.findByInciKeyAndActiveTrue(k).ifPresent(found::add);
            aliasRepo.findByAliasKey(k).flatMap(a -> substances.findById(a.getSubstanceId())).filter(RegulatedSubstance::isActive).ifPresent(found::add);
        }
        if (item.getCasNo() != null && !item.getCasNo().isBlank()) {
            List<Long> ids = casRepo.findByCas(item.getCasNo()).stream().map(RegulatedCas::getSubstanceId).toList();
            substances.findAllById(ids).stream().filter(RegulatedSubstance::isActive).forEach(found::add);
        }
        return strictest(found).map(x -> new Rule(x.getCatalog(), x.getMaxPct(), x.getSource()));
    }

    /** 禁用最严；限用里上限越小越严；限用没上限的最松。 */
    static Optional<RegulatedSubstance> strictest(java.util.Collection<RegulatedSubstance> list) {
        return list.stream().min(java.util.Comparator.<RegulatedSubstance>comparingInt(s -> s.getCatalog() == RegulatedCatalog.BANNED ? 0 : s.getMaxPct() != null ? 1 : 2)
                .thenComparing(s -> s.getMaxPct() == null ? BigDecimal.valueOf(Long.MAX_VALUE) : s.getMaxPct()));
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
        // 别名不能和别的条目的名称或别名重复：文件里的名称和别名、库里的名称和别名都要查，一次查完
        Map<String, String> fileOwner = new HashMap<>();
        for (RowReq r : req.rows()) {
            if (r.inciName() != null && !r.inciName().isBlank()) {
                fileOwner.putIfAbsent(key(r.inciName()), key(r.inciName()));
            }
        }
        Set<String> allAliasKeys = req.rows().stream().flatMap(r -> parseAliases(r.aliases()).stream()).map(RegulatoryService::key).collect(Collectors.toSet());
        Map<String, String> dbNameOwner = allAliasKeys.isEmpty() ? Map.of() : substances.findByInciKeyIn(allAliasKeys).stream()
                .collect(Collectors.toMap(RegulatedSubstance::getInciKey, RegulatedSubstance::getInciKey));
        Set<String> nameAndAliasKeys = new HashSet<>(allAliasKeys);
        nameAndAliasKeys.addAll(fileOwner.keySet());
        Map<String, Long> dbAliasOwner = nameAndAliasKeys.isEmpty() ? Map.of() : aliasRepo.findByAliasKeyIn(nameAndAliasKeys).stream()
                .collect(Collectors.toMap(RegulatedAlias::getAliasKey, RegulatedAlias::getSubstanceId));
        Map<Long, String> idToKey = new HashMap<>();
        if (!dbAliasOwner.isEmpty()) {
            substances.findAllById(new HashSet<>(dbAliasOwner.values())).forEach(s -> idToKey.put(s.getId(), s.getInciKey()));
        }
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
            String mine = key(inci);
            String other = dbAliasOwner.containsKey(mine) && !mine.equals(idToKey.get(dbAliasOwner.get(mine))) ? idToKey.get(dbAliasOwner.get(mine)) : null;
            if (other != null && !fileRedefinesAliases(req, other)) {
                errors.add(new RowError(line, inci, "INCI 已是目录里另一条的别名，请先从那一条去掉"));
                continue;
            }
            String conflict = null;
            for (String alias : parseAliases(r.aliases())) {
                String ak = key(alias);
                if (ak.length() > 200) {
                    conflict = "别名“" + alias + "”太长，每个最多 200 字";
                } else if (ak.equals(mine)) {
                    conflict = "别名“" + alias + "”和名称相同";
                } else if (fileOwner.containsKey(ak) && !fileOwner.get(ak).equals(mine)) {
                    conflict = "别名“" + alias + "”在文件里已是另一条的名称或别名";
                } else if (dbNameOwner.containsKey(ak)) {
                    conflict = "别名“" + alias + "”已是目录里另一条的名称";
                } else if (dbAliasOwner.containsKey(ak) && !mine.equals(idToKey.get(dbAliasOwner.get(ak))) && !fileRedefinesAliases(req, idToKey.get(dbAliasOwner.get(ak)))) {
                    conflict = "别名“" + alias + "”已是目录里另一条的别名";
                }
                if (conflict != null) {
                    break;
                }
                fileOwner.put(ak, mine);
            }
            if (conflict != null) {
                errors.add(new RowError(line, inci, conflict));
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
            // 文件里出现的已有条目，别名按文件重写：整批删一次
            List<Long> rewrite = existing.values().stream().map(RegulatedSubstance::getId).toList();
            if (!rewrite.isEmpty()) {
                aliasRepo.deleteBySubstanceIds(rewrite);
                casRepo.deleteBySubstanceIds(rewrite);
            }
            for (RowReq r : req.rows()) {
                RegulatedSubstance s = write(existing.getOrDefault(key(r.inciName()), new RegulatedSubstance()), r, by, now);
                for (String alias : parseAliases(r.aliases())) {
                    RegulatedAlias a = new RegulatedAlias();
                    a.setSubstanceId(s.getId());
                    a.setAliasKey(key(alias));
                    aliasRepo.save(a);
                }
                // CAS 列里能识别的号才用于对照，“无”之类的只保留文字
                for (String cas : com.cosmetic.scm.domain.rules.CasNumber.parseAll(r.casNo())) {
                    RegulatedCas c = new RegulatedCas();
                    c.setSubstanceId(s.getId());
                    c.setCas(cas);
                    casRepo.save(c);
                }
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
        String casBefore = String.join("; ", com.cosmetic.scm.domain.rules.CasNumber.parseAll(old.getCasNo()));
        String casAfter = String.join("; ", com.cosmetic.scm.domain.rules.CasNumber.parseAll(r.casNo()));
        if (!casBefore.equals(casAfter)) {
            list.add(new Change(inci, "CAS 号", casBefore.isEmpty() ? "无" : casBefore, casAfter.isEmpty() ? "无" : casAfter));
        }
        String before = aliasText(parseAliases(old.getAliases()));
        String after = aliasText(parseAliases(r.aliases()));
        if (!before.equals(after)) {
            list.add(new Change(inci, "别名", before.isEmpty() ? "无" : before, after.isEmpty() ? "无" : after));
        }
        return list;
    }

    /** 分号（中英文）分隔，去空、按对照键去重。 */
    static List<String> parseAliases(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        Map<String, String> byKey = new java.util.LinkedHashMap<>();
        for (String part : s.split("[;；]")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                byKey.putIfAbsent(key(t), t);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private static String aliasText(List<String> aliases) {
        return aliases.stream().sorted(java.util.Comparator.comparing(RegulatoryService::key)).collect(Collectors.joining("; "));
    }

    /** 那一条也在这次文件里：它的别名会按文件重写，库里的旧别名不算冲突。 */
    private static boolean fileRedefinesAliases(ImportReq req, String otherKey) {
        return otherKey != null && req.rows().stream().anyMatch(r -> r.inciName() != null && key(r.inciName()).equals(otherKey)
                && parseAliases(r.aliases()).stream().noneMatch(a -> key(a).equals(otherKey)));
    }

    private RegulatedSubstance write(RegulatedSubstance s, RowReq r, String by, LocalDateTime now) {
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
        List<String> al = parseAliases(r.aliases());
        s.setAliases(al.isEmpty() ? null : String.join("; ", al));
        return substances.save(s);
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
        long ruleCount = rules.size();
        Map<Long, RegulatedSubstance> byId = rules.values().stream().collect(Collectors.toMap(RegulatedSubstance::getId, x -> x));
        // 别名和 CAS 号也能对上：这两张表只有目录这么大，整张读出来
        Map<String, List<RegulatedSubstance>> byName = new HashMap<>();
        rules.forEach((k, v) -> byName.computeIfAbsent(k, x -> new ArrayList<>()).add(v));
        for (RegulatedAlias a : aliasRepo.findAll()) {
            RegulatedSubstance x = byId.get(a.getSubstanceId());
            if (x != null) {
                byName.computeIfAbsent(a.getAliasKey(), k -> new ArrayList<>()).add(x);
            }
        }
        Map<String, List<RegulatedSubstance>> byCas = new HashMap<>();
        for (RegulatedCas c : casRepo.findAll()) {
            RegulatedSubstance x = byId.get(c.getSubstanceId());
            if (x != null) {
                byCas.computeIfAbsent(c.getCas(), k -> new ArrayList<>()).add(x);
            }
        }
        List<ItemGap> gaps = new ArrayList<>();
        for (Item i : items.findAll()) {
            if (i.getItemType() != ItemType.RM || !i.isActive()) {
                continue;
            }
            boolean noInci = i.getInci() == null || i.getInci().isBlank();
            boolean noCas = i.getCasNo() == null || i.getCasNo().isBlank();
            if (noInci && noCas) {
                gaps.add(new ItemGap(i.getId(), i.getCode(), i.getName(), null, "NO_INCI", "INCI 和 CAS 号都没填，法规目录对不上，不会被校验"));
                continue;
            }
            List<RegulatedSubstance> found = new ArrayList<>();
            if (!noInci) {
                found.addAll(byName.getOrDefault(key(i.getInci()), List.of()));
            }
            if (!noCas) {
                found.addAll(byCas.getOrDefault(i.getCasNo(), List.of()));
            }
            RegulatedSubstance s = strictest(found).orElse(null);
            if (s == null) {
                continue;
            }
            if (s.getCatalog() == RegulatedCatalog.BANNED && !i.isBanned()) {
                gaps.add(new ItemGap(i.getId(), i.getCode(), i.getName(), i.getInci(), "BANNED_NOT_MARKED", "目录里是禁用（" + s.getInciName() + "），物料上没标。系统已按禁用处理，请把物料也改过来"));
            } else if (s.getMaxPct() != null && (i.getLimitPct() == null || i.getLimitPct().compareTo(s.getMaxPct()) > 0)) {
                gaps.add(new ItemGap(i.getId(), i.getCode(), i.getName(), i.getInci(), "LIMIT_LOOSER",
                        "目录上限 " + plain(s.getMaxPct()) + "%（" + s.getInciName() + "），物料上是 " + (i.getLimitPct() == null ? "没填" : plain(i.getLimitPct()) + "%") + "。系统已按目录校验"));
            }
        }
        return new Comparison(ruleCount, gaps, violations());
    }

    /**
     * 按当前目录，生效配方里已经不合规的物料。法规一变，已上市的产品就可能不合规，要有人看到。
     * 只查和目录有关的配方行，不扫全表。
     */
    @Transactional(readOnly = true)
    public List<Violation> violations() {
        List<Violation> out = new ArrayList<>();
        List<Object[]> matched = new ArrayList<>(bomLines.findLinesOfRegulatedItems());
        matched.addAll(bomLines.findLinesMatchedByAlias());
        matched.addAll(bomLines.findLinesMatchedByCas());
        // 同一配方行可能按名称、别名、CAS 号各对上一次，只报一次
        Set<String> reported = new HashSet<>();
        for (Object[] row : matched) {
            BomLine line = (BomLine) row[0];
            Item m = (Item) row[1];
            RegulatedSubstance s = (RegulatedSubstance) row[2];
            Bom bom = boms.findById(line.getBomId()).orElse(null);
            Item fg = bom == null ? null : items.findById(bom.getFgItemId()).orElse(null);
            if (fg == null || !fg.isActive()) {
                continue;
            }
            FormulaCompliance.Check c = FormulaCompliance.check(m.getName(), true, s.getCatalog() == RegulatedCatalog.BANNED, s.getMaxPct(), line.getQtyPer(), m.getUnit(), fg.getNetG());
            if (c.blocking() && reported.add(fg.getId() + "/" + m.getId())) {
                out.add(new Violation(fg.getId(), fg.getCode(), fg.getName(), m.getCode(), c.message()));
            }
        }
        return out;
    }

    private static SubstanceView view(RegulatedSubstance s) {
        return new SubstanceView(s.getId(), s.getCatalog(), s.getCatalog().getLabel(), s.getInciName(), s.getCnName(), s.getCasNo(), s.getMaxPct(), s.getConditions(),
                s.getSource(), s.isActive(), s.getUpdatedBy(), s.getUpdatedAt(), s.getAliases());
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
