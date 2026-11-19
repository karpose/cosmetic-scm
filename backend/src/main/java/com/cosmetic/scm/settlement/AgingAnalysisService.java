package com.cosmetic.scm.settlement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.StatementStatus;
import com.cosmetic.scm.domain.rules.SettlementRule;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Partner;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 应付（AP）与应收（AR）往来账龄分析：按往来单位汇总已审批未结清余额在
 * 未到期 / 1-30天 / 31-60天 / 61-90天 / 90天以上五个区间的分布，供财务月结与催收使用。
 */
@Service
@RequiredArgsConstructor
public class AgingAnalysisService {

    private final SupplierStatementRepository apRepo;
    private final CustomerStatementRepository arRepo;
    private final MasterLookup lookup;
    private final TimeProvider time;

    public record AgingRow(Long partnerId, String partnerCode, String partnerName, int openCount,
                           BigDecimal notDue, BigDecimal days1to30, BigDecimal days31to60,
                           BigDecimal days61to90, BigDecimal over90, BigDecimal total) {
    }

    public record AgingReport(LocalDate asOf, String side, BigDecimal notDue, BigDecimal days1to30,
                              BigDecimal days31to60, BigDecimal days61to90, BigDecimal over90,
                              BigDecimal total, List<AgingRow> rows) {
    }

    private static final class Acc {
        int count;
        BigDecimal notDue = BigDecimal.ZERO;
        BigDecimal d1 = BigDecimal.ZERO;
        BigDecimal d2 = BigDecimal.ZERO;
        BigDecimal d3 = BigDecimal.ZERO;
        BigDecimal d4 = BigDecimal.ZERO;

        void add(long overdueDays, BigDecimal amt) {
            count++;
            if (overdueDays <= 0) notDue = notDue.add(amt);
            else if (overdueDays <= 30) d1 = d1.add(amt);
            else if (overdueDays <= 60) d2 = d2.add(amt);
            else if (overdueDays <= 90) d3 = d3.add(amt);
            else d4 = d4.add(amt);
        }

        BigDecimal total() {
            return notDue.add(d1).add(d2).add(d3).add(d4);
        }
    }

    @Transactional(readOnly = true)
    public AgingReport apAging() {
        LocalDate today = time.today();
        Map<Long, Acc> map = new LinkedHashMap<>();
        for (SupplierStatement s : apRepo.findByStatusInOrderById(List.of(StatementStatus.APPROVED))) {
            BigDecimal open = s.outstandingAmount();
            if (open.signum() == 0) {
                continue;
            }
            long late = SettlementRule.overdueDays(s.effectiveDueDate(), today, false);
            map.computeIfAbsent(s.getSupplierId(), k -> new Acc()).add(late, open);
        }
        return build(today, "AP", map);
    }

    @Transactional(readOnly = true)
    public AgingReport arAging() {
        LocalDate today = time.today();
        Map<Long, Acc> map = new LinkedHashMap<>();
        for (CustomerStatement s : arRepo.findByStatusInOrderById(List.of("APPROVED"))) {
            BigDecimal open = s.outstandingAmount();
            if (open.signum() == 0) {
                continue;
            }
            long late = SettlementRule.overdueDays(s.getDueDate(), today, false);
            map.computeIfAbsent(s.getCustomerId(), k -> new Acc()).add(late, open);
        }
        return build(today, "AR", map);
    }

    private AgingReport build(LocalDate asOf, String side, Map<Long, Acc> map) {
        List<AgingRow> rows = new ArrayList<>();
        BigDecimal tNotDue = BigDecimal.ZERO;
        BigDecimal t1 = BigDecimal.ZERO;
        BigDecimal t2 = BigDecimal.ZERO;
        BigDecimal t3 = BigDecimal.ZERO;
        BigDecimal t4 = BigDecimal.ZERO;
        for (var e : map.entrySet()) {
            Partner p = lookup.partner(e.getKey());
            Acc a = e.getValue();
            BigDecimal sum = a.total();
            rows.add(new AgingRow(p.getId(), p.getCode(), p.getName(), a.count,
                    a.notDue, a.d1, a.d2, a.d3, a.d4, sum));
            tNotDue = tNotDue.add(a.notDue);
            t1 = t1.add(a.d1);
            t2 = t2.add(a.d2);
            t3 = t3.add(a.d3);
            t4 = t4.add(a.d4);
        }
        rows.sort(Comparator.comparing(AgingRow::total, Comparator.reverseOrder())
                .thenComparing(AgingRow::partnerCode));
        BigDecimal grand = tNotDue.add(t1).add(t2).add(t3).add(t4);
        return new AgingReport(asOf, side, tNotDue, t1, t2, t3, t4, grand, rows);
    }
}
