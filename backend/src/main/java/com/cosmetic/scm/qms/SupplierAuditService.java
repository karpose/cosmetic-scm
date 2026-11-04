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
import com.cosmetic.scm.inventory.Batch;
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

    /** dueState: NONE（没审核过）/ OK / DUE（快到期）/ OVERDUE；rejected / received：近 12 个月来料批次中拒收数 / 总数。 */
    public record PartnerRow(Long partnerId, String code, String name, String kindLabel, boolean qualified, boolean critical,
                             LocalDate lastAuditDate, String lastResultLabel, LocalDate nextAuditDate, String dueState,
                             Long dueDaysLeft, long received, long rejected) {
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
        LocalDate today = time.today();
        LocalDate since = today.minusMonths(STATS_MONTHS);
        List<Batch> all = batches.findAll();
        List<PartnerRow> out = new ArrayList<>();
        for (Partner p : partners.findAll()) {
            if (p.getKind() != PartnerKind.SUPPLIER && p.getKind() != PartnerKind.OEM) {
                continue;
            }
            List<SupplierAudit> history = audits.findByPartnerIdOrderByAuditDateDescIdDesc(p.getId());
            SupplierAudit last = history.isEmpty() ? null : history.get(0);
            LocalDate next = last == null ? null : last.getNextAuditDate();
            Long days = next == null ? null : ChronoUnit.DAYS.between(today, next);
            String state = last == null ? "NONE" : days == null ? "OK" : days < 0 ? "OVERDUE" : days <= DUE_WARN_DAYS ? "DUE" : "OK";
            long received = 0;
            long rejected = 0;
            for (Batch b : all) {
                if (p.getId().equals(b.getPartnerId()) && b.getMfgDate() != null && !b.getMfgDate().isBefore(since)) {
                    received++;
                    if (b.getStatus() == BatchStatus.REJECTED) {
                        rejected++;
                    }
                }
            }
            out.add(new PartnerRow(p.getId(), p.getCode(), p.getName(), p.getKind().getLabel(), p.isQualified(), p.isCriticalSupplier(),
                    last == null ? null : last.getAuditDate(), last == null ? null : last.getResult().getLabel(), next, state, days, received, rejected));
        }
        out.sort(Comparator.comparing(PartnerRow::code));
        return out;
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
