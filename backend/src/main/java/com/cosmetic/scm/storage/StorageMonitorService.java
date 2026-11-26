package com.cosmetic.scm.storage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.FieldDiff;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.CaseStatus;
import com.cosmetic.scm.domain.DeviationLevel;
import com.cosmetic.scm.domain.EnvSource;
import com.cosmetic.scm.domain.RetainStatus;
import com.cosmetic.scm.domain.StorageAreaKind;
import com.cosmetic.scm.domain.rules.StorageCondition;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.Stock;
import com.cosmetic.scm.inventory.StockRepository;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Warehouse;
import com.cosmetic.scm.master.WarehouseRepository;
import com.cosmetic.scm.qms.Deviation;
import com.cosmetic.scm.qms.DeviationRepository;
import com.cosmetic.scm.qms.DeviationService;
import com.cosmetic.scm.qms.RetainSample;
import com.cosmetic.scm.qms.RetainSampleRepository;
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

/**
 * 贮存区域和温湿度记录（《化妆品生产质量管理规范》第五十六条留样贮存条件、第五十九条贮存和运输；检查要点委托生产版第 16、20 项）。
 * 质量部门给每个区域（仓库的某个区、留样室）设温湿度范围，仓库手工录入或导入记录仪数据；超出范围的记录按次归并，每次自动生成一份偏差：
 * 同一区域、偏差还没关闭、和这次第一条或最后一条超限记录相隔不超过 scm.storage-env.excursion-merge-hours（默认 24）小时的算同一次。
 * 记录只增不改，录错的由质量部门作废（写明原因）。录入、导入、作废、修改区域都先锁区域行（事务第一句）。
 * 规则见 docs/PLM-默认规则.md 的“贮存环境”。
 */
@Service
@RequiredArgsConstructor
public class StorageMonitorService {

    /** 记录页最多返回多少条（合计按全部算）。 */
    public static final int LIST_CAP = 1000;

    public static final int IMPORT_MAX = 10000;

    /** 趋势图一次最多看多少天。 */
    public static final int TREND_MAX_DAYS = 92;

    /** 录入时间最多比现在晚几分钟（电脑时钟有误差）。 */
    private static final int FUTURE_TOLERANCE_MINUTES = 5;

    private final StorageAreaRepository areas;
    private final EnvRecordRepository records;
    private final EnvExcursionRepository excursions;
    private final DeviationRepository deviationRepo;
    private final DeviationService deviationService;
    private final WarehouseRepository warehouses;
    private final StockRepository stocks;
    private final BatchRepository batches;
    private final RetainSampleRepository retains;
    private final MasterLookup lookup;
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;
    private final Validator validator;

    @Value("${scm.storage-env.excursion-merge-hours:24}")
    private int mergeHours;

    @Value("${scm.storage-env.excursion-deviation-level:MINOR}")
    private DeviationLevel deviationLevel;

    // ---------- 请求和视图 ----------

    public record AreaReq(@NotBlank(message = "请填写区域编码") @Size(max = 40, message = "区域编码最多 40 个字") String code,
                          @NotBlank(message = "请填写区域名称") @Size(max = 100, message = "区域名称最多 100 个字") String name,
                          @NotNull(message = "请选择区域类型") StorageAreaKind kind,
                          Long warehouseId,
                          @DecimalMin(value = "-80", message = "温度下限不能低于 -80℃") @DecimalMax(value = "100", message = "温度下限不能高于 100℃")
                          @Digits(integer = 3, fraction = 1, message = "温度最多 1 位小数") BigDecimal tempMin,
                          @DecimalMin(value = "-80", message = "温度上限不能低于 -80℃") @DecimalMax(value = "100", message = "温度上限不能高于 100℃")
                          @Digits(integer = 3, fraction = 1, message = "温度最多 1 位小数") BigDecimal tempMax,
                          @DecimalMin(value = "0", message = "湿度在 0–100% 之间") @DecimalMax(value = "100", message = "湿度在 0–100% 之间")
                          @Digits(integer = 3, fraction = 1, message = "湿度最多 1 位小数") BigDecimal rhMin,
                          @DecimalMin(value = "0", message = "湿度在 0–100% 之间") @DecimalMax(value = "100", message = "湿度在 0–100% 之间")
                          @Digits(integer = 3, fraction = 1, message = "湿度最多 1 位小数") BigDecimal rhMax,
                          boolean dailyRequired,
                          @NotNull(message = "请选择开始记录的日期") LocalDate monitorFrom,
                          Boolean active,
                          @Size(max = 300, message = "备注最多 300 个字") String note) {
    }

    public record RecordReq(@NotNull(message = "请选择记录时间") LocalDateTime recordedAt,
                            @DecimalMin(value = "-80", message = "温度不能低于 -80℃") @DecimalMax(value = "100", message = "温度不能高于 100℃")
                            @Digits(integer = 3, fraction = 1, message = "温度最多 1 位小数") BigDecimal temp,
                            @DecimalMin(value = "0", message = "相对湿度在 0–100% 之间") @DecimalMax(value = "100", message = "相对湿度在 0–100% 之间")
                            @Digits(integer = 3, fraction = 1, message = "相对湿度最多 1 位小数") BigDecimal rh,
                            @Size(max = 60, message = "记录仪编号最多 60 个字") String device,
                            @Size(max = 300, message = "备注最多 300 个字") String note) {
    }

    /** 导入的一行。小数位多的四舍五入到 1 位（记录仪常导出 2 位），所以不加 @Digits。 */
    public record ImportRow(@NotNull(message = "请填写时间") LocalDateTime recordedAt,
                            @DecimalMin(value = "-80", message = "温度不能低于 -80℃") @DecimalMax(value = "100", message = "温度不能高于 100℃") BigDecimal temp,
                            @DecimalMin(value = "0", message = "相对湿度在 0–100% 之间") @DecimalMax(value = "100", message = "相对湿度在 0–100% 之间") BigDecimal rh,
                            @Size(max = 60, message = "记录仪编号最多 60 个字") String device,
                            @Size(max = 300, message = "备注最多 300 个字") String note) {
    }

    public record ImportReq(@NotEmpty(message = "没有可导入的行") @Size(max = IMPORT_MAX, message = "一次最多导入 10000 行，请分成几个文件") List<ImportRow> rows,
                            boolean dryRun) {
    }

    public record RowError(int line, String code, String message) {
    }

    /** created：要写入（预检）或已写入的条数；skipped：库里这一分钟已有记录，跳过；newExcursions：新的超限次数（每次一份偏差）。 */
    public record ImportResult(int total, int created, int skipped, int outOfRange, int newExcursions, boolean applied, List<RowError> errors) {
    }

    public record VoidReq(@NotBlank(message = "请写明作废原因") @Size(max = 300, message = "作废原因最多 300 个字") String reason) {
    }

    public record Option(String code, String label) {
    }

    /**
     * missing：要求每天记录、昨天没有记录；stillOut：最近一条有效记录超限。todayCount：今天的有效记录条数。
     */
    public record AreaView(Long id, String code, String name, StorageAreaKind kind, String kindLabel, Long warehouseId, String warehouseName,
                           BigDecimal tempMin, BigDecimal tempMax, BigDecimal rhMin, BigDecimal rhMax, String limitText, boolean dailyRequired,
                           LocalDate monitorFrom, boolean active, String note, LocalDateTime lastAt, BigDecimal lastTemp, BigDecimal lastRh,
                           Boolean lastInRange, long todayCount, boolean recordedYesterday, boolean missing, boolean stillOut, String createdBy,
                           LocalDateTime createdAt, String updatedBy, LocalDateTime updatedAt) {
    }

    public record RecordView(Long id, Long areaId, String areaCode, String areaName, LocalDateTime recordedAt, BigDecimal temp, BigDecimal rh,
                             String limitText, boolean inRange, String problem, EnvSource source, String sourceLabel, String device, String note,
                             Long excursionId, String excursionNo, Long deviationId, String deviationNo, boolean voided, String voidReason,
                             String voidedBy, LocalDateTime voidedAt, String createdBy, LocalDateTime createdAt) {
    }

    public record RecordList(List<RecordView> rows, long total, int cap, LocalDate from, LocalDate to) {
    }

    public record TrendPoint(LocalDateTime t, BigDecimal temp, BigDecimal rh, boolean inRange) {
    }

    public record Trend(Long areaId, String areaName, String limitText, BigDecimal tempMin, BigDecimal tempMax, BigDecimal rhMin, BigDecimal rhMax,
                        LocalDate from, LocalDate to, List<TrendPoint> points) {
    }

    /** stillOut：区域最近一条有效记录还属于这次超限。 */
    public record ExcursionView(Long id, String excursionNo, Long areaId, String areaCode, String areaName, StorageAreaKind kind, String kindLabel,
                                LocalDateTime startedAt, LocalDateTime lastAt, int readingCount, String peakText, String limitText, Long deviationId,
                                String deviationNo, CaseStatus deviationStatus, String deviationStatusLabel, boolean stillOut, boolean voided,
                                String createdBy, LocalDateTime createdAt) {
    }

    /** 超限详情：超限记录，以及现在还在这个区域的批次（仓储区按关联仓库的库存，留样区按留样地点和区域名称或编码相同的在库留样）。 */
    public record AffectedRow(String batchNo, String itemCode, String itemName, String qty, String status) {
    }

    public record ExcursionDetail(ExcursionView excursion, List<RecordView> readings, List<AffectedRow> affected, String affectedNote) {
    }

    /** 总览待办用：漏记的区域和仍超限的区域。 */
    public record TodoRow(String kind, String areaName, String areaCode, String detail) {
    }

    // ---------- 区域 ----------

    @Transactional(readOnly = true)
    public List<Option> kinds() {
        return java.util.Arrays.stream(StorageAreaKind.values()).map(k -> new Option(k.name(), k.getLabel())).toList();
    }

    @Transactional(readOnly = true)
    public List<AreaView> areas() {
        return areaViews(areas.findAllByOrderByCodeAsc());
    }

    @Transactional
    public AreaView createArea(AreaReq req) {
        String code = req.code().trim();
        if (areas.existsByCode(code)) {
            throw new BizException("区域编码 " + code + " 已存在");
        }
        StorageArea a = new StorageArea();
        a.setCode(code);
        a.setActive(true);
        a.setCreatedBy(currentUser.usernameOrSystem());
        a.setCreatedAt(time.now());
        apply(a, req);
        areas.save(a);
        audit.log("新增贮存区域", a.getCode(), a.getName() + "（" + a.getKind().getLabel() + "），" + a.limits().text()
                + (a.isDailyRequired() ? "，每天记录，从 " + a.getMonitorFrom() + " 起" : ""));
        return areaViews(List.of(a)).get(0);
    }

    @Transactional
    public AreaView updateArea(Long id, AreaReq req) {
        StorageArea a = areas.lockById(id).orElseThrow(() -> BizException.notFound("贮存区域"));
        String code = req.code().trim();
        if (areas.existsByCodeAndIdNot(code, id)) {
            throw new BizException("区域编码 " + code + " 已存在");
        }
        Map<String, Object> before = fields(a);
        a.setCode(code);
        if (req.active() != null) {
            a.setActive(req.active());
        }
        apply(a, req);
        a.setUpdatedBy(currentUser.usernameOrSystem());
        a.setUpdatedAt(time.now());
        areas.save(a);
        String diff = FieldDiff.of(before, fields(a));
        audit.log("修改贮存区域", a.getCode(), diff.isEmpty() ? "没有变化" : diff);
        return areaViews(List.of(a)).get(0);
    }

    private void apply(StorageArea a, AreaReq req) {
        StorageCondition.Limits l = new StorageCondition.Limits(req.tempMin(), req.tempMax(), req.rhMin(), req.rhMax());
        String bad = StorageCondition.checkLimits(l);
        if (bad != null) {
            throw new BizException(bad);
        }
        if (req.warehouseId() != null && !warehouses.existsById(req.warehouseId())) {
            throw BizException.notFound("仓库");
        }
        a.setName(req.name().trim());
        a.setKind(req.kind());
        a.setWarehouseId(req.warehouseId());
        a.setTempMin(req.tempMin());
        a.setTempMax(req.tempMax());
        a.setRhMin(req.rhMin());
        a.setRhMax(req.rhMax());
        a.setDailyRequired(req.dailyRequired());
        a.setMonitorFrom(req.monitorFrom());
        a.setNote(blankToNull(req.note()));
    }

    private Map<String, Object> fields(StorageArea a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("编码", a.getCode());
        m.put("名称", a.getName());
        m.put("类型", a.getKind() == null ? null : a.getKind().getLabel());
        Warehouse w = a.getWarehouseId() == null ? null : lookup.warehouse(a.getWarehouseId());
        m.put("所在仓库", w == null ? null : w.getName());
        m.put("温度下限", a.getTempMin());
        m.put("温度上限", a.getTempMax());
        m.put("湿度下限", a.getRhMin());
        m.put("湿度上限", a.getRhMax());
        m.put("每天记录", a.isDailyRequired());
        m.put("开始记录日期", a.getMonitorFrom());
        m.put("启用", a.isActive());
        m.put("备注", a.getNote());
        return m;
    }

    private List<AreaView> areaViews(List<StorageArea> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        LocalDate today = time.today();
        LocalDate yesterday = today.minusDays(1);
        Map<Long, Map<LocalDate, long[]>> stats = dayStats(yesterday, today);
        Map<Long, Warehouse> whs = lookup.warehouseMap();
        List<AreaView> out = new ArrayList<>();
        for (StorageArea a : list) {
            EnvRecord last = records.findFirstByAreaIdAndVoidedFalseOrderByRecordedAtDescIdDesc(a.getId()).orElse(null);
            Map<LocalDate, long[]> byDay = stats.getOrDefault(a.getId(), Map.of());
            boolean yes = byDay.containsKey(yesterday);
            boolean missing = a.isActive() && a.isDailyRequired() && !a.getMonitorFrom().isAfter(yesterday) && !yes;
            Warehouse w = a.getWarehouseId() == null ? null : whs.get(a.getWarehouseId());
            out.add(new AreaView(a.getId(), a.getCode(), a.getName(), a.getKind(), a.getKind().getLabel(), a.getWarehouseId(), w == null ? null : w.getName(),
                    a.getTempMin(), a.getTempMax(), a.getRhMin(), a.getRhMax(), a.limits().text(), a.isDailyRequired(), a.getMonitorFrom(), a.isActive(),
                    a.getNote(), last == null ? null : last.getRecordedAt(), last == null ? null : last.getTemp(), last == null ? null : last.getRh(),
                    last == null ? null : last.isInRange(), byDay.containsKey(today) ? byDay.get(today)[0] : 0, yes, missing,
                    a.isActive() && last != null && !last.isInRange(), a.getCreatedBy(), a.getCreatedAt(), a.getUpdatedBy(), a.getUpdatedAt()));
        }
        return out;
    }

    /** 区域 id → 日期 → {有效记录数, 超限数}。 */
    private Map<Long, Map<LocalDate, long[]>> dayStats(LocalDate from, LocalDate to) {
        Map<Long, Map<LocalDate, long[]>> out = new HashMap<>();
        for (Object[] r : records.dayStats(from, to)) {
            out.computeIfAbsent((Long) r[0], k -> new HashMap<>()).put((LocalDate) r[1],
                    new long[] {((Number) r[2]).longValue(), r[3] == null ? 0 : ((Number) r[3]).longValue()});
        }
        return out;
    }

    // ---------- 录入和导入 ----------

    @Transactional
    public RecordView record(Long areaId, RecordReq req) {
        StorageArea a = areas.lockById(areaId).orElseThrow(() -> BizException.notFound("贮存区域"));
        if (!a.isActive()) {
            throw new BizException("区域 " + a.getName() + " 已停用，不能再录入");
        }
        LocalDateTime at = req.recordedAt().truncatedTo(ChronoUnit.MINUTES);
        if (at.isAfter(time.now().plusMinutes(FUTURE_TOLERANCE_MINUTES))) {
            throw new BizException("记录时间不能晚于现在");
        }
        String missing = StorageCondition.missingReading(a.limits(), req.temp(), req.rh());
        if (missing != null) {
            throw new BizException(missing);
        }
        if (records.existsByDupKey(dupKey(a.getId(), at))) {
            throw new BizException(a.getName() + " " + fmt(at) + " 已有记录；录错了请让质量部门作废后重录");
        }
        EnvRecord r = newRecord(a, at, req.temp(), req.rh(), EnvSource.MANUAL, req.device(), req.note());
        List<EnvExcursion> created = group(a, List.of(r));
        records.save(r);
        List<String> problems = StorageCondition.violations(r.limits(), r.getTemp(), r.getRh());
        EnvExcursion e = r.getExcursionId() == null ? null : excursions.findById(r.getExcursionId()).orElse(null);
        audit.log("录入温湿度", a.getCode(), a.getName() + " " + fmt(at) + " " + readingText(r.getTemp(), r.getRh())
                + (problems.isEmpty() ? "，在范围内" : "，超限：" + String.join("；", problems)
                + (e == null ? "" : created.isEmpty() ? "，并入 " + e.getExcursionNo() : "，生成 " + e.getExcursionNo())));
        return recordViews(List.of(r)).get(0);
    }

    @Transactional
    public ImportResult importRows(Long areaId, ImportReq req) {
        StorageArea a = areas.lockById(areaId).orElseThrow(() -> BizException.notFound("贮存区域"));
        if (!a.isActive()) {
            throw new BizException("区域 " + a.getName() + " 已停用，不能再导入");
        }
        LocalDateTime latest = time.now().plusMinutes(FUTURE_TOLERANCE_MINUTES);
        List<RowError> errors = new ArrayList<>();
        Map<LocalDateTime, Integer> seen = new HashMap<>();
        List<ImportRow> valid = new ArrayList<>();
        List<LocalDateTime> times = new ArrayList<>();
        for (int i = 0; i < req.rows().size(); i++) {
            ImportRow row = req.rows().get(i);
            int line = i + 2;
            if (row == null) {
                errors.add(new RowError(line, null, "空行"));
                continue;
            }
            Set<ConstraintViolation<ImportRow>> bad = validator.validate(row);
            if (!bad.isEmpty()) {
                errors.add(new RowError(line, row.recordedAt() == null ? null : fmt(row.recordedAt()),
                        bad.stream().map(ConstraintViolation::getMessage).sorted().collect(Collectors.joining("；"))));
                continue;
            }
            LocalDateTime at = row.recordedAt().truncatedTo(ChronoUnit.MINUTES);
            String code = fmt(at);
            if (at.isAfter(latest)) {
                errors.add(new RowError(line, code, "时间晚于现在"));
                continue;
            }
            BigDecimal temp = round1(row.temp());
            BigDecimal rh = round1(row.rh());
            String missing = StorageCondition.missingReading(a.limits(), temp, rh);
            if (missing != null) {
                errors.add(new RowError(line, code, missing));
                continue;
            }
            Integer dup = seen.putIfAbsent(at, line);
            if (dup != null) {
                errors.add(new RowError(line, code, "和第 " + dup + " 行是同一分钟，文件里重复"));
                continue;
            }
            valid.add(new ImportRow(at, temp, rh, blankToNull(row.device()), blankToNull(row.note())));
            times.add(at);
        }
        if (!errors.isEmpty()) {
            return new ImportResult(req.rows().size(), 0, 0, 0, 0, false, errors);
        }
        LocalDateTime min = times.stream().min(Comparator.naturalOrder()).orElseThrow();
        LocalDateTime max = times.stream().max(Comparator.naturalOrder()).orElseThrow();
        Set<LocalDateTime> taken = new HashSet<>(records.takenTimes(a.getId(), min, max));
        List<EnvRecord> fresh = new ArrayList<>();
        for (ImportRow row : valid) {
            if (!taken.contains(row.recordedAt())) {
                fresh.add(newRecord(a, row.recordedAt(), row.temp(), row.rh(), EnvSource.IMPORT, row.device(), row.note()));
            }
        }
        fresh.sort(Comparator.comparing(EnvRecord::getRecordedAt));
        int skipped = valid.size() - fresh.size();
        int out = (int) fresh.stream().filter(r -> !r.isInRange()).count();
        if (req.dryRun()) {
            return new ImportResult(req.rows().size(), fresh.size(), skipped, out, simulate(a, fresh, false).newCount(), false, List.of());
        }
        if (fresh.isEmpty()) {
            return new ImportResult(req.rows().size(), 0, skipped, 0, 0, true, List.of());
        }
        // 先建超限和偏差（持久化上下文里实体还少），最后一次写入记录；循环里不查库
        List<EnvExcursion> created = group(a, fresh);
        records.saveAll(fresh);
        audit.log("导入温湿度记录", a.getCode(), a.getName() + "：" + fresh.size() + " 条，" + fmt(fresh.get(0).getRecordedAt()) + " 至 "
                + fmt(fresh.get(fresh.size() - 1).getRecordedAt()) + (skipped > 0 ? "，已有 " + skipped + " 条跳过" : "")
                + (out > 0 ? "，超限 " + out + " 条" : "，都在范围内")
                + (created.isEmpty() ? "" : "，新的超限 " + created.size() + " 次：" + created.stream().map(EnvExcursion::getExcursionNo).collect(Collectors.joining("、"))));
        return new ImportResult(req.rows().size(), fresh.size(), skipped, out, created.size(), true, List.of());
    }

    private EnvRecord newRecord(StorageArea a, LocalDateTime at, BigDecimal temp, BigDecimal rh, EnvSource source, String device, String note) {
        EnvRecord r = new EnvRecord();
        r.setAreaId(a.getId());
        r.setRecordedAt(at);
        r.setRecordedOn(at.toLocalDate());
        r.setTemp(temp);
        r.setRh(rh);
        r.setTempMin(a.getTempMin());
        r.setTempMax(a.getTempMax());
        r.setRhMin(a.getRhMin());
        r.setRhMax(a.getRhMax());
        r.setInRange(StorageCondition.violations(a.limits(), temp, rh).isEmpty());
        r.setSource(source);
        r.setDevice(blankToNull(device));
        r.setNote(blankToNull(note));
        r.setDupKey(dupKey(a.getId(), at));
        r.setVoided(false);
        r.setCreatedBy(currentUser.usernameOrSystem());
        r.setCreatedAt(time.now());
        return r;
    }

    // ---------- 超限归并 ----------

    /** windows：前 existing.size() 个是库里已有、偏差还没关闭的超限，后面是这批新建的；assignment：每条超限记录对应的下标。 */
    private record Simulation(List<EnvExcursion> existing, List<StorageCondition.Window> windows, List<EnvRecord> outs, List<Integer> assignment) {

        int newCount() {
            return windows.size() - existing.size();
        }
    }

    /** lock：写入时锁住候选超限的偏差行，等别人关闭偏差提交后再判断状态（关闭偏差也锁这一行）。 */
    private Simulation simulate(StorageArea a, List<EnvRecord> fresh, boolean lock) {
        List<EnvRecord> outs = fresh.stream().filter(r -> !r.isInRange()).sorted(Comparator.comparing(EnvRecord::getRecordedAt)).toList();
        if (outs.isEmpty()) {
            return new Simulation(List.of(), new ArrayList<>(), outs, List.of());
        }
        Duration w = Duration.ofHours(mergeHours);
        List<EnvExcursion> near = excursions.findByAreaIdAndVoidedFalseAndLastAtGreaterThanEqualAndStartedAtLessThanEqualOrderByIdAsc(a.getId(),
                outs.get(0).getRecordedAt().minus(w), outs.get(outs.size() - 1).getRecordedAt().plus(w));
        List<EnvExcursion> open = new ArrayList<>();
        for (EnvExcursion e : near) {
            if (e.getDeviationId() == null) {
                continue;
            }
            Deviation d = (lock ? deviationRepo.lockById(e.getDeviationId()) : deviationRepo.findById(e.getDeviationId())).orElse(null);
            if (d != null && d.getStatus() == CaseStatus.OPEN) {
                open.add(e);
            }
        }
        List<StorageCondition.Window> windows = new ArrayList<>();
        open.forEach(e -> windows.add(new StorageCondition.Window(e.getStartedAt(), e.getLastAt(), true)));
        List<Integer> assignment = StorageCondition.assign(windows, outs.stream().map(EnvRecord::getRecordedAt).toList(), w);
        return new Simulation(open, windows, outs, assignment);
    }

    /** 把超限记录并进已有的超限或新建（每次新建一份偏差），给记录填上 excursionId。返回新建的超限。 */
    private List<EnvExcursion> group(StorageArea a, List<EnvRecord> fresh) {
        Simulation s = simulate(a, fresh, true);
        if (s.outs().isEmpty()) {
            return List.of();
        }
        Map<Integer, List<EnvRecord>> members = new LinkedHashMap<>();
        for (int i = 0; i < s.outs().size(); i++) {
            members.computeIfAbsent(s.assignment().get(i), k -> new ArrayList<>()).add(s.outs().get(i));
        }
        List<EnvExcursion> created = new ArrayList<>();
        for (Map.Entry<Integer, List<EnvRecord>> m : members.entrySet()) {
            int idx = m.getKey();
            List<EnvRecord> list = m.getValue();
            EnvExcursion e;
            if (idx < s.existing().size()) {
                e = s.existing().get(idx);
                e.setReadingCount(e.getReadingCount() + list.size());
                widen(e, list);
            } else {
                e = new EnvExcursion();
                e.setExcursionNo(docNo.next("TH", time.today()));
                e.setAreaId(a.getId());
                e.setReadingCount(list.size());
                e.setVoided(false);
                e.setCreatedBy(currentUser.usernameOrSystem());
                e.setCreatedAt(time.now());
                widen(e, list);
                EnvRecord first = list.get(0);
                String desc = a.getName() + "（" + a.getCode() + "）" + fmt(first.getRecordedAt()) + " 起超出范围："
                        + String.join("；", StorageCondition.violations(first.limits(), first.getTemp(), first.getRh()))
                        + (list.size() > 1 ? "；共 " + list.size() + " 条超限记录，到 " + fmt(e.getLastAt()) + "，" + peakText(e) : "")
                        + "。范围：" + first.limits().text() + "。请评估对区域内产品" + (a.getKind() == StorageAreaKind.RETAIN ? "（留样）" : "") + "的影响。";
                Deviation d = deviationService.createForStorage(e.getExcursionNo(), deviationLevel, first.getRecordedOn(), "温湿度超限：" + a.getName(),
                        desc.length() > 1000 ? desc.substring(0, 1000) : desc);
                e.setDeviationId(d.getId());
                excursions.save(e);
                created.add(e);
            }
            Long id = e.getId();
            list.forEach(r -> r.setExcursionId(id));
        }
        return created;
    }

    private static void widen(EnvExcursion e, List<EnvRecord> list) {
        for (EnvRecord r : list) {
            if (e.getStartedAt() == null || r.getRecordedAt().isBefore(e.getStartedAt())) {
                e.setStartedAt(r.getRecordedAt());
            }
            if (e.getLastAt() == null || r.getRecordedAt().isAfter(e.getLastAt())) {
                e.setLastAt(r.getRecordedAt());
            }
            // 只记超出范围的那一项（温度超了、湿度正常的，湿度不算进超限读数）
            if (StorageCondition.tempOut(r.limits(), r.getTemp())) {
                e.setTempLow(min(e.getTempLow(), r.getTemp()));
                e.setTempHigh(max(e.getTempHigh(), r.getTemp()));
            }
            if (StorageCondition.rhOut(r.limits(), r.getRh())) {
                e.setRhLow(min(e.getRhLow(), r.getRh()));
                e.setRhHigh(max(e.getRhHigh(), r.getRh()));
            }
        }
    }

    // ---------- 作废 ----------

    @Transactional
    public RecordView voidRecord(Long areaId, Long recordId, VoidReq req) {
        StorageArea a = areas.lockById(areaId).orElseThrow(() -> BizException.notFound("贮存区域"));
        EnvRecord r = records.lockByIdAndAreaId(recordId, areaId).orElseThrow(() -> BizException.notFound("温湿度记录"));
        if (r.isVoided()) {
            throw new BizException("这条记录已经作废");
        }
        r.setVoided(true);
        r.setVoidReason(req.reason().trim());
        r.setVoidedBy(currentUser.usernameOrSystem());
        r.setVoidedAt(time.now());
        r.setDupKey(null);
        records.saveAndFlush(r);
        String extra = "";
        if (r.getExcursionId() != null) {
            EnvExcursion e = excursions.findById(r.getExcursionId()).orElseThrow();
            List<EnvRecord> left = records.findByExcursionIdAndVoidedFalseOrderByRecordedAtAsc(e.getId());
            if (left.isEmpty()) {
                e.setVoided(true);
                Deviation d = e.getDeviationId() == null ? null : deviationRepo.findById(e.getDeviationId()).orElse(null);
                extra = "；" + e.getExcursionNo() + " 已没有有效的超限记录" + (d == null || d.getStatus() != CaseStatus.OPEN ? ""
                        : "，偏差 " + d.getDeviationNo() + " 请质量部门评估后关闭");
            } else {
                e.setStartedAt(null);
                e.setLastAt(null);
                e.setTempLow(null);
                e.setTempHigh(null);
                e.setRhLow(null);
                e.setRhHigh(null);
                e.setReadingCount(left.size());
                widen(e, left);
                extra = "；" + e.getExcursionNo() + " 剩 " + left.size() + " 条超限记录";
            }
            excursions.save(e);
        }
        audit.log("作废温湿度记录", a.getCode(), a.getName() + " " + fmt(r.getRecordedAt()) + " " + readingText(r.getTemp(), r.getRh()) + extra, r.getVoidReason());
        return recordViews(List.of(r)).get(0);
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public RecordList list(Long areaId, LocalDate from, LocalDate to) {
        LocalDate t = to == null ? time.today() : to;
        LocalDate f = from == null ? t.minusDays(6) : from;
        if (f.isAfter(t)) {
            throw new BizException("开始日期不能晚于结束日期");
        }
        List<EnvRecord> list = areaId == null ? records.findTop1000ByRecordedOnBetweenOrderByRecordedAtDescIdDesc(f, t)
                : records.findTop1000ByAreaIdAndRecordedOnBetweenOrderByRecordedAtDescIdDesc(areaId, f, t);
        long total = areaId == null ? records.countByRecordedOnBetween(f, t) : records.countByAreaIdAndRecordedOnBetween(areaId, f, t);
        return new RecordList(recordViews(list), total, LIST_CAP, f, t);
    }

    @Transactional(readOnly = true)
    public Trend trend(Long areaId, LocalDate from, LocalDate to) {
        StorageArea a = areas.findById(areaId).orElseThrow(() -> BizException.notFound("贮存区域"));
        LocalDate t = to == null ? time.today() : to;
        LocalDate f = from == null ? t.minusDays(6) : from;
        if (f.isAfter(t)) {
            throw new BizException("开始日期不能晚于结束日期");
        }
        if (f.plusDays(TREND_MAX_DAYS).isBefore(t)) {
            throw new BizException("趋势图一次最多看 " + TREND_MAX_DAYS + " 天，请缩小日期范围");
        }
        List<TrendPoint> points = records.findTop20000ByAreaIdAndVoidedFalseAndRecordedOnBetweenOrderByRecordedAtAsc(areaId, f, t).stream()
                .map(r -> new TrendPoint(r.getRecordedAt(), r.getTemp(), r.getRh(), r.isInRange())).toList();
        return new Trend(a.getId(), a.getName(), a.limits().text(), a.getTempMin(), a.getTempMax(), a.getRhMin(), a.getRhMax(), f, t, points);
    }

    /** 全部记录（含作废的），导出用：按日期范围，超过上限由导出报错。 */
    @Transactional(readOnly = true)
    public List<RecordView> exportRows(LocalDate from, LocalDate to) {
        return recordViews(records.findByRecordedOnBetweenOrderByAreaIdAscRecordedAtAscIdAsc(from, to));
    }

    @Transactional(readOnly = true)
    public List<ExcursionView> excursions() {
        return excursionViews(excursions.findTop300ByOrderByIdDesc());
    }

    @Transactional(readOnly = true)
    public List<ExcursionView> excursionsAll() {
        return excursionViews(excursions.findAllByOrderByIdDesc());
    }

    @Transactional(readOnly = true)
    public ExcursionDetail excursion(Long id) {
        EnvExcursion e = excursions.findById(id).orElseThrow(() -> BizException.notFound("超限记录"));
        StorageArea a = areas.findById(e.getAreaId()).orElseThrow();
        List<AffectedRow> affected = new ArrayList<>();
        String note;
        if (a.getKind() == StorageAreaKind.RETAIN) {
            List<RetainSample> held = retains.findHeldIn(RetainStatus.HELD, likeEscape(a.getName()), a.getCode());
            Map<Long, Batch> bs = batches.findAllById(held.stream().map(RetainSample::getBatchId).distinct().toList()).stream()
                    .collect(Collectors.toMap(Batch::getId, Function.identity()));
            for (RetainSample s : held) {
                Batch b = bs.get(s.getBatchId());
                Item i = b == null ? null : lookup.itemMap().get(b.getItemId());
                affected.add(new AffectedRow(b == null ? "—" : b.getBatchNo(), i == null ? "" : i.getCode(), i == null ? "" : i.getName(),
                        s.getRemainingQty() + " 件留样", "保存至 " + s.getRetainUntil()));
            }
            note = "留样地点以“" + a.getName() + "”开头或等于“" + a.getCode() + "”、现在还在库的留样。";
        } else if (a.getWarehouseId() != null) {
            List<Stock> on = stocks.findByWarehouseIdAndQtyGreaterThan(a.getWarehouseId(), BigDecimal.ZERO);
            Map<Long, Batch> bs = batches.findAllById(on.stream().map(Stock::getBatchId).distinct().toList()).stream()
                    .collect(Collectors.toMap(Batch::getId, Function.identity()));
            Map<Long, Item> items = lookup.itemMap();
            for (Stock s : on) {
                Batch b = bs.get(s.getBatchId());
                Item i = items.get(s.getItemId());
                affected.add(new AffectedRow(b == null ? "—" : b.getBatchNo(), i == null ? "" : i.getCode(), i == null ? "" : i.getName(),
                        s.getQty().stripTrailingZeros().toPlainString() + (i == null || i.getUnit() == null ? "" : " " + i.getUnit()),
                        b == null ? "" : b.getStatus().getLabel()));
            }
            affected.sort(Comparator.comparing(AffectedRow::itemCode).thenComparing(AffectedRow::batchNo));
            Warehouse w = lookup.warehouse(a.getWarehouseId());
            note = "仓库“" + (w == null ? "" : w.getName()) + "”现在的库存（不是超限当时的），整个仓库的批次都列出来，请按实际存放位置评估。";
        } else {
            note = "这个区域没有关联仓库，请按实际存放的产品评估。";
        }
        return new ExcursionDetail(excursionViews(List.of(e)).get(0), recordViews(records.findByExcursionIdAndVoidedFalseOrderByRecordedAtAsc(id)),
                affected, note);
    }

    /** 总览待办：要求每天记录、昨天没有记录的区域；最近一条记录还超限的区域。 */
    @Transactional(readOnly = true)
    public List<TodoRow> todos() {
        List<StorageArea> active = areas.findByActiveTrueOrderByCodeAsc();
        if (active.isEmpty()) {
            return List.of();
        }
        LocalDate yesterday = time.today().minusDays(1);
        Map<Long, Map<LocalDate, long[]>> stats = dayStats(yesterday, yesterday);
        List<StorageArea> missing = active.stream().filter(a -> a.isDailyRequired() && !a.getMonitorFrom().isAfter(yesterday) && !stats.containsKey(a.getId())).toList();
        Map<Long, LocalDateTime> lastAt = new HashMap<>();
        if (!missing.isEmpty()) {
            for (Object[] r : records.lastRecordedAt(missing.stream().map(StorageArea::getId).toList())) {
                lastAt.put((Long) r[0], (LocalDateTime) r[1]);
            }
        }
        List<TodoRow> out = new ArrayList<>();
        for (StorageArea a : missing) {
            LocalDateTime last = lastAt.get(a.getId());
            out.add(new TodoRow("MISSING", a.getName(), a.getCode(), "昨天（" + yesterday + "）没有温湿度记录，"
                    + (last == null ? "还没有记录过" : "最近一条是 " + fmt(last))));
        }
        for (StorageArea a : active) {
            EnvRecord last = records.findFirstByAreaIdAndVoidedFalseOrderByRecordedAtDescIdDesc(a.getId()).orElse(null);
            if (last != null && !last.isInRange()) {
                out.add(new TodoRow("OUT", a.getName(), a.getCode(), fmt(last.getRecordedAt()) + " "
                        + String.join("；", StorageCondition.violations(last.limits(), last.getTemp(), last.getRh())) + "，之后还没有恢复正常的记录"));
            }
        }
        return out;
    }

    // ---------- 视图 ----------

    private List<RecordView> recordViews(List<EnvRecord> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, StorageArea> as = areas.findAllById(list.stream().map(EnvRecord::getAreaId).distinct().toList()).stream()
                .collect(Collectors.toMap(StorageArea::getId, Function.identity()));
        Map<Long, EnvExcursion> es = excursions.findAllById(list.stream().map(EnvRecord::getExcursionId).filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(EnvExcursion::getId, Function.identity()));
        Map<Long, Deviation> ds = deviationRepo.findAllById(es.values().stream().map(EnvExcursion::getDeviationId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(Deviation::getId, Function.identity()));
        return list.stream().map(r -> {
            StorageArea a = as.get(r.getAreaId());
            EnvExcursion e = r.getExcursionId() == null ? null : es.get(r.getExcursionId());
            Deviation d = e == null || e.getDeviationId() == null ? null : ds.get(e.getDeviationId());
            List<String> problems = StorageCondition.violations(r.limits(), r.getTemp(), r.getRh());
            return new RecordView(r.getId(), r.getAreaId(), a == null ? null : a.getCode(), a == null ? null : a.getName(), r.getRecordedAt(), r.getTemp(),
                    r.getRh(), r.limits().text(), r.isInRange(), problems.isEmpty() ? null : String.join("；", problems), r.getSource(), r.getSource().getLabel(),
                    r.getDevice(), r.getNote(), r.getExcursionId(), e == null ? null : e.getExcursionNo(), d == null ? null : d.getId(),
                    d == null ? null : d.getDeviationNo(), r.isVoided(), r.getVoidReason(), r.getVoidedBy(), r.getVoidedAt(), r.getCreatedBy(), r.getCreatedAt());
        }).toList();
    }

    private List<ExcursionView> excursionViews(List<EnvExcursion> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, StorageArea> as = areas.findAllById(list.stream().map(EnvExcursion::getAreaId).distinct().toList()).stream()
                .collect(Collectors.toMap(StorageArea::getId, Function.identity()));
        Map<Long, Deviation> ds = deviationRepo.findAllById(list.stream().map(EnvExcursion::getDeviationId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(Deviation::getId, Function.identity()));
        Map<Long, EnvRecord> latest = new HashMap<>();
        for (Long areaId : as.keySet()) {
            records.findFirstByAreaIdAndVoidedFalseOrderByRecordedAtDescIdDesc(areaId).ifPresent(r -> latest.put(areaId, r));
        }
        return list.stream().map(e -> {
            StorageArea a = as.get(e.getAreaId());
            Deviation d = e.getDeviationId() == null ? null : ds.get(e.getDeviationId());
            EnvRecord last = latest.get(e.getAreaId());
            boolean stillOut = !e.isVoided() && last != null && !last.isInRange() && Objects.equals(last.getExcursionId(), e.getId());
            return new ExcursionView(e.getId(), e.getExcursionNo(), e.getAreaId(), a.getCode(), a.getName(), a.getKind(), a.getKind().getLabel(),
                    e.getStartedAt(), e.getLastAt(), e.getReadingCount(), peakText(e), a.limits().text(), d == null ? null : d.getId(),
                    d == null ? null : d.getDeviationNo(), d == null ? null : d.getStatus(), d == null ? null : d.getStatus().getLabel(), stillOut,
                    e.isVoided(), e.getCreatedBy(), e.getCreatedAt());
        }).toList();
    }

    // ---------- 工具 ----------

    static String peakText(EnvExcursion e) {
        List<String> parts = new ArrayList<>();
        if (e.getTempLow() != null) {
            parts.add(e.getTempLow().compareTo(e.getTempHigh()) == 0 ? "温度 " + StorageCondition.plain(e.getTempLow()) + "℃"
                    : "温度 " + StorageCondition.plain(e.getTempLow()) + "–" + StorageCondition.plain(e.getTempHigh()) + "℃");
        }
        if (e.getRhLow() != null) {
            parts.add(e.getRhLow().compareTo(e.getRhHigh()) == 0 ? "湿度 " + StorageCondition.plain(e.getRhLow()) + "%"
                    : "湿度 " + StorageCondition.plain(e.getRhLow()) + "–" + StorageCondition.plain(e.getRhHigh()) + "%");
        }
        return String.join("，", parts);
    }

    private static String readingText(BigDecimal temp, BigDecimal rh) {
        List<String> parts = new ArrayList<>();
        if (temp != null) {
            parts.add(StorageCondition.plain(temp) + "℃");
        }
        if (rh != null) {
            parts.add(StorageCondition.plain(rh) + "%");
        }
        return String.join(" / ", parts);
    }

    static String dupKey(Long areaId, LocalDateTime at) {
        return areaId + ":" + at.truncatedTo(ChronoUnit.MINUTES);
    }

    static String fmt(LocalDateTime t) {
        return t.toLocalDate() + " " + String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    /** like 的通配符按字面匹配（区域名称里可能有 % 或 _）。 */
    private static String likeEscape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static BigDecimal round1(BigDecimal v) {
        return v == null ? null : v.setScale(1, RoundingMode.HALF_UP);
    }

    private static BigDecimal min(BigDecimal a, BigDecimal b) {
        if (b == null) {
            return a;
        }
        return a == null || b.compareTo(a) < 0 ? b : a;
    }

    private static BigDecimal max(BigDecimal a, BigDecimal b) {
        if (b == null) {
            return a;
        }
        return a == null || b.compareTo(a) > 0 ? b : a;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public int mergeHours() {
        return mergeHours;
    }
}
