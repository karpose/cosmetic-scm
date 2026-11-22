package com.cosmetic.scm.qms;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.AuditResult;
import com.cosmetic.scm.domain.AuditType;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.PoStatus;
import com.cosmetic.scm.domain.rules.SupplierScore;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.PartnerRepository;
import com.cosmetic.scm.security.AppUser;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 供应商和受托厂审核。结论直接决定能不能继续下单：不合格移出合格名录，合格恢复；
 * 有条件合格不改变名录状态。到期提醒按下次审核日期计算。
 */
@Service
@RequiredArgsConstructor
public class SupplierAuditService {

    /** 下次审核日期前多少天开始提醒。 */
    public static final int DUE_WARN_DAYS = 30;
    /** 来料合格率的统计窗口。 */
    private static final int STATS_MONTHS = 12;

    private final SupplierAuditRepository audits;
    private final PartnerRepository partners;
    private final BatchRepository batches;
    private final MasterLookup lookup;
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final com.cosmetic.scm.purchase.PurchaseOrderRepository purchaseOrders;

    public record AuditReq(@NotNull(message = "请选择被审核单位") Long partnerId,
                           @NotNull(message = "请选择审核日期") LocalDate auditDate,
                           @NotNull AuditType auditType,
                           @Min(0) @Max(100) Integer score,
                           @NotNull AuditResult result,
                           @Size(max = 1000) String findings,
                           @NotBlank(message = "请填写审核员") @Size(max = 100) String auditor,
                           LocalDate nextAuditDate,
                           @NotBlank(message = "请输入签批密码") String password) {
    }

    public record AuditView(Long id, String auditNo, Long partnerId, String partnerName, LocalDate auditDate,
                            AuditType auditType, String auditTypeLabel, Integer score, AuditResult result,
                            String resultLabel, String findings, String auditor, LocalDate nextAuditDate,
                            String signedBy, LocalDateTime signedAt) {
    }

    /** dueState: NONE（没审核过）/ OK / DUE（快到期）/ OVERDUE；licenseState: EXPIRED/EXPIRING/OK/NONE；complianceRisk: HIGH/MEDIUM/OK。 */
    public record PartnerRow(Long partnerId, String code, String name, String kindLabel, boolean qualified, boolean critical,
                             LocalDate lastAuditDate, String lastResultLabel, LocalDate nextAuditDate, String dueState,
                             Long dueDaysLeft, long received, long rejected, long dueTotal, long onTime,
                             Integer score, String grade,
                             String licenseNo, LocalDate licenseExpiry, String licenseState, Long licenseDaysLeft,
                             String complianceRisk, String complianceRiskLabel) {
    }

    @Transactional(readOnly = true)
    public List<AuditView> list() {
        return audits.findTop300ByOrderByIdDesc().stream().map(this::toView).toList();
    }

    /** 全部审核记录，导出用。 */
    @Transactional(readOnly = true)
    public List<AuditView> listAll() {
        return audits.findAllByOrderByIdDesc().stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public List<PartnerRow> partnerRows() {
        Stats st = stats();
        List<PartnerRow> out = new ArrayList<>();
        for (Partner p : partners.findAll()) {
            if (p.getKind() == PartnerKind.SUPPLIER || p.getKind() == PartnerKind.OEM) {
                out.add(toRow(p, st));
            }
        }
        out.sort(Comparator.comparingInt((PartnerRow r) -> riskOrder(r.complianceRisk())).thenComparing(PartnerRow::code));
        return out;
    }

    /** 单个单位的一行，供应商门户用。 */
    @Transactional(readOnly = true)
    public PartnerRow rowOf(Long partnerId) {
        return toRow(lookup.partner(partnerId), stats());
    }

    private record Stats(LocalDate today, Map<Long, Object[]> batches, Map<Long, Object[]> delivery) {
    }

    private Stats stats() {
        LocalDate today = time.today();
        LocalDate since = today.minusMonths(STATS_MONTHS);
        Map<Long, Object[]> bm = new java.util.HashMap<>();
        for (Object[] row : batches.statsByPartner(since, BatchStatus.REJECTED)) {
            bm.put((Long) row[0], row);
        }
        Map<Long, Object[]> dm = new java.util.HashMap<>();
        for (Object[] row : purchaseOrders.deliveryStats(since, today, List.of(PoStatus.RECEIVED, PoStatus.CLOSED), PoStatus.RECEIVED, PoStatus.CANCELLED)) {
            dm.put((Long) row[0], row);
        }
        return new Stats(today, bm, dm);
    }

    private PartnerRow toRow(Partner p, Stats st) {
        List<SupplierAudit> history = audits.findByPartnerIdOrderByAuditDateDescIdDesc(p.getId());
        SupplierAudit last = history.isEmpty() ? null : history.get(0);
        LocalDate next = last == null ? null : last.getNextAuditDate();
        Long days = next == null ? null : ChronoUnit.DAYS.between(st.today(), next);
        String state = last == null ? "NONE" : days == null ? "OK" : days < 0 ? "OVERDUE" : days <= DUE_WARN_DAYS ? "DUE" : "OK";
        Object[] b = st.batches().get(p.getId());
        long received = b == null ? 0 : ((Number) b[1]).longValue();
        long rejected = b == null ? 0 : ((Number) b[2]).longValue();
        Object[] d = st.delivery().get(p.getId());
        long dueTotal = d == null ? 0 : ((Number) d[1]).longValue();
        long onTime = d == null ? 0 : ((Number) d[2]).longValue();
        SupplierScore.Result score = SupplierScore.of(received, rejected, dueTotal, onTime,
                last == null ? null : SupplierScore.AuditLevel.valueOf(last.getResult().name()));
        LocalDate licExp = p.getLicenseExpiry();
        Long licDays = licExp == null ? null : ChronoUnit.DAYS.between(st.today(), licExp);
        String licState = licExp == null ? "NONE" : licDays < 0 ? "EXPIRED" : licDays <= 60 ? "EXPIRING" : "OK";
        String risk;
        String riskLabel;
        if (!p.isQualified() || "EXPIRED".equals(licState) || "OVERDUE".equals(state)) {
            risk = "HIGH";
            riskLabel = "高风险";
        } else if ("EXPIRING".equals(licState) || "DUE".equals(state)
                || ("NONE".equals(state) && (p.isCriticalSupplier() || p.getKind() == PartnerKind.OEM))) {
            risk = "MEDIUM";
            riskLabel = "需关注";
        } else {
            risk = "OK";
            riskLabel = "合规";
        }
        return new PartnerRow(p.getId(), p.getCode(), p.getName(), p.getKind().getLabel(), p.isQualified(), p.isCriticalSupplier(),
                last == null ? null : last.getAuditDate(), last == null ? null : last.getResult().getLabel(), next, state, days,
                received, rejected, dueTotal, onTime, score == null ? null : score.score(), score == null ? null : score.grade(),
                p.getLicenseNo(), licExp, licState, licDays, risk, riskLabel);
    }

    private static int riskOrder(String risk) {
        return switch (risk) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            default -> 2;
        };
    }

    @Transactional
    public AuditView create(AuditReq req, AppUser signer) {
        Partner p = lookup.partner(req.partnerId());
        if (p.getKind() != PartnerKind.SUPPLIER && p.getKind() != PartnerKind.OEM) {
            throw new BizException(p.getName() + " 不是供应商或受托厂，不需要审核");
        }
        LocalDate today = time.today();
        if (req.auditDate().isAfter(today)) {
            throw new BizException("审核日期不能晚于今天");
        }
        if (req.result() != AuditResult.FAIL && req.nextAuditDate() == null) {
            throw new BizException("请填写下次审核日期");
        }
        if (req.nextAuditDate() != null && !req.nextAuditDate().isAfter(req.auditDate())) {
            throw new BizException("下次审核日期要晚于本次审核日期");
        }
        if (req.result() != AuditResult.PASS && (req.findings() == null || req.findings().isBlank())) {
            throw new BizException("结论不是合格时请写明发现的问题");
        }
        SupplierAudit a = new SupplierAudit();
        a.setAuditNo(docNo.next("SA", today));
        a.setPartnerId(p.getId());
        a.setAuditDate(req.auditDate());
        a.setAuditType(req.auditType());
        a.setScore(req.score());
        a.setResult(req.result());
        a.setFindings(req.findings() == null || req.findings().isBlank() ? null : req.findings().trim());
        a.setAuditor(req.auditor().trim());
        a.setNextAuditDate(req.nextAuditDate());
        a.setSignedBy(signer.getDisplayName());
        a.setSignedAt(time.now());
        audits.save(a);

        boolean before = p.isQualified();
        if (req.result() == AuditResult.FAIL) {
            p.setQualified(false);
        } else if (req.result() == AuditResult.PASS) {
            p.setQualified(true);
        }
        partners.save(p);
        String nameList = before == p.isQualified() ? "" : p.isQualified() ? "，恢复合格名录" : "，移出合格名录并停止下单";
        audit.log("供应商审核", a.getAuditNo(), p.getName() + " " + req.result().getLabel()
                + (req.score() == null ? "" : "（" + req.score() + " 分）") + nameList + "，签批人 " + signer.getDisplayName(), a.getFindings());
        return toView(a);
    }

    private AuditView toView(SupplierAudit a) {
        Map<Long, Partner> pm = lookup.partnerMap();
        return new AuditView(a.getId(), a.getAuditNo(), a.getPartnerId(), pm.get(a.getPartnerId()).getName(), a.getAuditDate(),
                a.getAuditType(), a.getAuditType().getLabel(), a.getScore(), a.getResult(), a.getResult().getLabel(),
                a.getFindings(), a.getAuditor(), a.getNextAuditDate(), a.getSignedBy(), a.getSignedAt());
    }
}
