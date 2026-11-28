package com.cosmetic.scm.qms;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.FieldDiff;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.RetainSiteKind;
import com.cosmetic.scm.domain.RetainStatus;
import com.cosmetic.scm.domain.rules.RetainSiteRule;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.security.CurrentUser;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * 留样地点和向药品监督管理部门的报告（《化妆品生产质量管理规范》第五十六条；检查要点委托生产版第 15 项）。
 * 质量部门登记留样地点（住所、主要经营场所、所在地的其他经营场所）；其他经营场所按首次留样日起 20 个工作日算报告截止日（{@link RetainSiteRule}），
 * 登记报告日期、受理部门和回执。所有写操作先锁地点行（事务第一句）。已经留样或报告过的地点不能改地址和类型，搬迁要新登记一个地点。
 * 规则见 docs/PLM-默认规则.md 的“留样地点”。
 */
@Service
@RequiredArgsConstructor
public class RetainSiteService {

    /** 一次最多给多少份留样指定地点。 */
    public static final int ASSIGN_MAX = 500;

    private final RetainSiteRepository sites;
    private final RetainSampleRepository samples;
    private final BatchRepository batches;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record SiteReq(@NotBlank(message = "请填写地点编码") @Size(max = 40, message = "地点编码最多 40 个字") String code,
                          @NotBlank(message = "请填写地点名称") @Size(max = 100, message = "地点名称最多 100 个字") String name,
                          @NotBlank(message = "请填写留样地点的地址") @Size(max = 300, message = "地址最多 300 个字") String address,
                          @NotNull(message = "请选择地点类型") RetainSiteKind kind,
                          boolean sameLocality,
                          LocalDate usedSince,
                          Boolean active,
                          Boolean defaultSite,
                          @Size(max = 300, message = "备注最多 300 个字") String note) {
    }

    public record ReportReq(@NotNull(message = "请选择报告日期") LocalDate reportDate,
                            @NotBlank(message = "请填写受理报告的药品监督管理部门") @Size(max = 100, message = "部门名称最多 100 个字") String authority,
                            @Size(max = 60, message = "回执或受理编号最多 60 个字") String ref,
                            @Size(max = 300, message = "说明最多 300 个字") String note) {
    }

    public record VoidReportReq(@NotBlank(message = "请写明作废原因") @Size(max = 300, message = "作废原因最多 300 个字") String reason) {
    }

    public record AssignReq(@NotEmpty(message = "请选择要指定地点的留样") @Size(max = ASSIGN_MAX, message = "一次最多指定 500 份留样") List<Long> sampleIds) {
    }

    public record Option(String code, String label) {
    }

    /**
     * firstRetainDate：上线前开始使用的日期和台账里最早一份留样取早的；deadline：报告截止日（按周一至周五估算，只有其他经营场所有）；
     * fixed：已经留样或报告过，地址和类型不能再改。
     */
    public record SiteView(Long id, String code, String name, String address, RetainSiteKind kind, String kindLabel, boolean sameLocality,
                           LocalDate usedSince, boolean active, boolean defaultSite, String note, LocalDate firstRetainDate, LocalDate deadline,
                           RetainSiteRule.State state, String stateLabel, boolean problem, long sampleCount, long heldCount,
                           LocalDate reportDate, String reportAuthority, String reportRef, String reportNote, String reportedBy, LocalDateTime reportedAt,
                           boolean fixed, String createdBy, LocalDateTime createdAt, String updatedBy, LocalDateTime updatedAt) {
    }

    /** 总览待办：kind 为 REPORT（待报告、逾期）、NOT_ALLOWED（不在所在地却还有留样）、UNASSIGNED（在库留样没有指定地点）。 */
    public record TodoRow(String kind, boolean bad, String title, String ref, String detail) {
    }

    private record Usage(LocalDate first, long count, long held) {
    }

    @Transactional(readOnly = true)
    public List<Option> kinds() {
        return java.util.Arrays.stream(RetainSiteKind.values()).map(k -> new Option(k.name(), k.getLabel())).toList();
    }

    @Transactional(readOnly = true)
    public List<SiteView> list() {
        return views(sites.findAllByOrderByCodeAsc());
    }

    @Transactional
    public SiteView create(SiteReq req) {
        String code = req.code().trim();
        if (sites.existsByCode(code)) {
            throw new BizException("地点编码 " + code + " 已存在");
        }
        RetainSite s = new RetainSite();
        s.setCode(code);
        s.setActive(true);
        s.setCreatedBy(currentUser.usernameOrSystem());
        s.setCreatedAt(time.now());
        apply(s, req);
        boolean makeDefault = Boolean.TRUE.equals(req.defaultSite()) || (req.defaultSite() == null && sites.findDefaultIds().isEmpty());
        if (makeDefault) {
            clearOtherDefaults(null);
            s.setDefaultKey(1);
        }
        sites.save(s);
        audit.log("新增留样地点", s.getCode(), s.getName() + "（" + s.getKind().getLabel() + "），" + s.getAddress()
                + (s.getUsedSince() == null ? "" : "，" + s.getUsedSince() + " 起使用") + (s.isDefaultSite() ? "，设为默认地点" : ""));
        return views(List.of(s)).get(0);
    }

    @Transactional
    public SiteView update(Long id, SiteReq req) {
        RetainSite s = sites.lockById(id).orElseThrow(() -> BizException.notFound("留样地点"));
        String code = req.code().trim();
        if (sites.existsByCodeAndIdNot(code, id)) {
            throw new BizException("地点编码 " + code + " 已存在");
        }
        boolean fixed = fixed(s, usage().get(s.getId()));
        boolean placeChanged = req.kind() != s.getKind() || !req.address().trim().equals(s.getAddress())
                || (req.kind() == RetainSiteKind.OTHER_PREMISES && req.sameLocality() != s.isSameLocality());
        if (fixed && placeChanged) {
            throw new BizException("“" + s.getName() + "”已经" + (s.getReportDate() != null ? "报告过" : "存放过留样")
                    + "，地址和类型不能改。地点搬迁或报告的信息有变的，请新登记一个留样地点，把这个地点停用");
        }
        Map<String, Object> before = fields(s);
        s.setCode(code);
        if (req.active() != null) {
            s.setActive(req.active());
        }
        apply(s, req);
        if (!s.isActive() || Boolean.FALSE.equals(req.defaultSite())) {
            s.setDefaultKey(null);
        } else if (Boolean.TRUE.equals(req.defaultSite()) && !s.isDefaultSite()) {
            clearOtherDefaults(s.getId());
            s.setDefaultKey(1);
        }
        s.setUpdatedBy(currentUser.usernameOrSystem());
        s.setUpdatedAt(time.now());
        sites.save(s);
        String diff = FieldDiff.of(before, fields(s));
        audit.log("修改留样地点", s.getCode(), diff.isEmpty() ? "没有变化" : diff);
        return views(List.of(s)).get(0);
    }

    /** 默认地点只有一个（default_key 唯一）：先把原来的默认地点锁住、清掉并写库，再设新的。 */
    private void clearOtherDefaults(Long keepId) {
        for (Long id : sites.findDefaultIds()) {
            if (id.equals(keepId)) {
                continue;
            }
            sites.lockById(id).ifPresent(o -> {
                o.setDefaultKey(null);
                sites.saveAndFlush(o);
                audit.log("修改留样地点", o.getCode(), "默认地点 是 → 否");
            });
        }
    }

    private void apply(RetainSite s, SiteReq req) {
        if (req.kind() == RetainSiteKind.OTHER_PREMISES && !req.sameLocality()) {
            throw new BizException("其他经营场所要在住所或主要经营场所所在地（GMP 第五十六条），请核对后勾选“在住所或主要经营场所所在地”；不在所在地的不能作为留样地点");
        }
        if (req.usedSince() != null && req.usedSince().isAfter(time.today())) {
            throw new BizException("开始使用的日期不能晚于今天");
        }
        s.setName(req.name().trim());
        s.setAddress(req.address().trim());
        s.setKind(req.kind());
        s.setSameLocality(req.kind() != RetainSiteKind.OTHER_PREMISES || req.sameLocality());
        s.setUsedSince(req.usedSince());
        s.setNote(blankToNull(req.note()));
    }

    /** 登记向药品监督管理部门的报告。只有其他经营场所要报告；登记后不能改，录错的先作废。 */
    @Transactional
    public SiteView report(Long id, ReportReq req) {
        RetainSite s = sites.lockById(id).orElseThrow(() -> BizException.notFound("留样地点"));
        if (!RetainSiteRule.reportRequired(s.getKind())) {
            throw new BizException("“" + s.getName() + "”是" + s.getKind().getLabel() + "，不需要报告");
        }
        if (s.getReportDate() != null) {
            throw new BizException("“" + s.getName() + "”已登记 " + s.getReportDate() + " 的报告；登记有误的，请先作废这条报告再重新登记");
        }
        if (req.reportDate().isAfter(time.today())) {
            throw new BizException("报告日期不能晚于今天");
        }
        s.setReportDate(req.reportDate());
        s.setReportAuthority(req.authority().trim());
        s.setReportRef(blankToNull(req.ref()));
        s.setReportNote(blankToNull(req.note()));
        s.setReportedBy(currentUser.usernameOrSystem());
        s.setReportedAt(time.now());
        sites.save(s);
        SiteView v = views(List.of(s)).get(0);
        audit.log("登记留样地点报告", s.getCode(), s.getName() + "，" + s.getReportDate() + " 报告" + s.getReportAuthority()
                + (s.getReportRef() == null ? "" : "，回执 " + s.getReportRef())
                + (v.firstRetainDate() == null ? "，还没有留样" : "，首次留样 " + v.firstRetainDate() + "，截止 " + v.deadline())
                + (v.state() == RetainSiteRule.State.REPORTED_LATE ? "（晚于截止日）" : ""), s.getReportNote());
        return v;
    }

    @Transactional
    public SiteView voidReport(Long id, VoidReportReq req) {
        RetainSite s = sites.lockById(id).orElseThrow(() -> BizException.notFound("留样地点"));
        if (s.getReportDate() == null) {
            throw new BizException("“" + s.getName() + "”还没有登记报告");
        }
        String was = s.getReportDate() + " 报告" + s.getReportAuthority() + (s.getReportRef() == null ? "" : "，回执 " + s.getReportRef())
                + "，登记人 " + s.getReportedBy();
        s.setReportDate(null);
        s.setReportAuthority(null);
        s.setReportRef(null);
        s.setReportNote(null);
        s.setReportedBy(null);
        s.setReportedAt(null);
        s.setUpdatedBy(currentUser.usernameOrSystem());
        s.setUpdatedAt(time.now());
        sites.save(s);
        audit.log("作废留样地点报告", s.getCode(), s.getName() + "，作废的报告：" + was, req.reason().trim());
        return views(List.of(s)).get(0);
    }

    /**
     * 给还没有地点的留样（V58 以前的、登记地点之前的）指定地点。已经有地点的不能改。
     * 先锁地点（事务第一句），再按 id 顺序锁留样。
     */
    @Transactional
    public SiteView assign(Long id, AssignReq req) {
        RetainSite s = sites.lockById(id).orElseThrow(() -> BizException.notFound("留样地点"));
        if (!s.isActive()) {
            throw new BizException("“" + s.getName() + "”已停用，不能再存放留样");
        }
        List<Long> ids = req.sampleIds().stream().filter(Objects::nonNull).distinct().sorted().toList();
        List<RetainSample> locked = new ArrayList<>();
        for (Long sid : ids) {
            RetainSample r = samples.lockById(sid).orElseThrow(() -> BizException.notFound("留样记录"));
            if (r.getSiteId() != null) {
                throw new BizException("有留样已经指定了地点，不能再改（留样记录 " + sid + "）");
            }
            locked.add(r);
        }
        locked.forEach(r -> r.setSiteId(s.getId()));
        samples.saveAll(locked);
        Map<Long, Batch> bm = batches.findAllById(locked.stream().map(RetainSample::getBatchId).toList()).stream()
                .collect(Collectors.toMap(Batch::getId, Function.identity()));
        String nos = locked.stream().map(r -> bm.containsKey(r.getBatchId()) ? bm.get(r.getBatchId()).getBatchNo() : "#" + r.getId())
                .collect(Collectors.joining("、"));
        SiteView v = views(List.of(s)).get(0);
        audit.log("指定留样地点", s.getCode(), s.getName() + "：" + locked.size() + " 份留样（" + nos + "）"
                + (v.firstRetainDate() == null ? "" : "，首次留样日 " + v.firstRetainDate()));
        return v;
    }

    /** 总览待办：要报告却没报告的地点、不在所在地还有留样的地点、在库却没有指定地点的留样。 */
    @Transactional(readOnly = true)
    public List<TodoRow> todos() {
        List<TodoRow> out = new ArrayList<>();
        List<RetainSite> all = sites.findAllByOrderByCodeAsc();
        for (SiteView v : views(all)) {
            if (v.state() == RetainSiteRule.State.PENDING || v.state() == RetainSiteRule.State.OVERDUE) {
                boolean late = v.state() == RetainSiteRule.State.OVERDUE;
                out.add(new TodoRow("REPORT", late, v.name(), v.code(), "其他经营场所，" + v.firstRetainDate() + " 首次留样，"
                        + (late ? "报告截止日 " + v.deadline() + " 已过，请尽快" : "请在 " + v.deadline() + " 前（按周一至周五估算）")
                        + "向所在地负责药品监督管理的部门报告留样地点的地址等信息"));
            } else if (v.state() == RetainSiteRule.State.NOT_ALLOWED && v.heldCount() > 0) {
                out.add(new TodoRow("NOT_ALLOWED", true, v.name(), v.code(), "不在住所或主要经营场所所在地，还存放着 " + v.heldCount() + " 份留样"));
            }
        }
        if (all.stream().anyMatch(RetainSite::isActive)) {
            long n = samples.countBySiteIdIsNullAndStatus(RetainStatus.HELD);
            if (n > 0) {
                out.add(new TodoRow("UNASSIGNED", false, "留样台账", String.valueOf(n), n + " 份在库的留样没有指定留样地点，请在“留样台账”里指定"));
            }
        }
        return out;
    }

    private Map<Long, Usage> usage() {
        Map<Long, Usage> m = new HashMap<>();
        for (Object[] r : samples.siteUsage(RetainStatus.HELD)) {
            m.put((Long) r[0], new Usage(((LocalDateTime) r[1]).toLocalDate(), ((Number) r[2]).longValue(), r[3] == null ? 0 : ((Number) r[3]).longValue()));
        }
        return m;
    }

    private static boolean fixed(RetainSite s, Usage u) {
        return s.getReportDate() != null || (u != null && u.count() > 0);
    }

    private List<SiteView> views(List<RetainSite> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, Usage> usage = usage();
        LocalDate today = time.today();
        return list.stream().map(s -> {
            Usage u = usage.get(s.getId());
            LocalDate first = RetainSiteRule.earliest(s.getUsedSince(), u == null ? null : u.first());
            RetainSiteRule.State st = RetainSiteRule.state(s.getKind(), s.isSameLocality(), first, s.getReportDate(), today);
            return new SiteView(s.getId(), s.getCode(), s.getName(), s.getAddress(), s.getKind(), s.getKind().getLabel(), s.isSameLocality(),
                    s.getUsedSince(), s.isActive(), s.isDefaultSite(), s.getNote(), first, RetainSiteRule.deadline(s.getKind(), first), st, st.getLabel(), st.problem(),
                    u == null ? 0 : u.count(), u == null ? 0 : u.held(), s.getReportDate(), s.getReportAuthority(), s.getReportRef(), s.getReportNote(),
                    s.getReportedBy(), s.getReportedAt(), fixed(s, u), s.getCreatedBy(), s.getCreatedAt(), s.getUpdatedBy(), s.getUpdatedAt());
        }).toList();
    }

    private static Map<String, Object> fields(RetainSite s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("编码", s.getCode());
        m.put("名称", s.getName());
        m.put("地址", s.getAddress());
        m.put("类型", s.getKind() == null ? null : s.getKind().getLabel());
        m.put("在所在地", s.isSameLocality());
        m.put("开始使用日期", s.getUsedSince());
        m.put("启用", s.isActive());
        m.put("默认地点", s.isDefaultSite());
        m.put("备注", s.getNote());
        return m;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
