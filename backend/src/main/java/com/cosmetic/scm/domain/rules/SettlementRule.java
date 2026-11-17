package com.cosmetic.scm.domain.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 对账结算纯业务规则：
 * 1. 多税率自动拆分汇总（含税金额、不含税金额、税额）
 * 2. 账期与到期日计算、逾期天数判定
 * 3. 多发票（含蓝字发票 BLUE、红字冲减发票 RED）金额与去重校验
 */
public final class SettlementRule {

    public static final int DEFAULT_CREDIT_DAYS = 30;
    public static final int MAX_CREDIT_DAYS = 365;

    private SettlementRule() {
    }

    /** 单行金额与税率输入。 */
    public record TaxLineInput(BigDecimal taxRate, BigDecimal amount, BigDecimal taxAmount) {
    }

    /** 按税率分组汇总结果。 */
    public record TaxBreakdown(BigDecimal taxRate, BigDecimal goodsAmount, BigDecimal netAmount, BigDecimal taxAmount, int lineCount) {
    }

    /** 发票校验输入。 */
    public record InvoiceInput(String invoiceNo, String invoiceType, LocalDate invoiceDate, BigDecimal taxRate, BigDecimal amount) {
    }

    /**
     * 按税率升序拆分汇总各档税率的含税金额、不含税金额和税额。
     */
    public static List<TaxBreakdown> breakdownByTaxRate(List<TaxLineInput> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        Map<BigDecimal, TaxBreakdown> grouped = new TreeMap<>(Comparator.naturalOrder());
        for (TaxLineInput line : lines) {
            BigDecimal rate = (line.taxRate() == null ? BigDecimal.ZERO : line.taxRate()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal amt = line.amount() == null ? BigDecimal.ZERO : line.amount();
            BigDecimal tax = line.taxAmount() == null ? taxIn(amt, rate) : line.taxAmount();
            BigDecimal net = amt.subtract(tax);
            TaxBreakdown prev = grouped.get(rate);
            if (prev == null) {
                grouped.put(rate, new TaxBreakdown(rate, amt, net, tax, 1));
            } else {
                grouped.put(rate, new TaxBreakdown(
                        rate,
                        prev.goodsAmount().add(amt),
                        prev.netAmount().add(net),
                        prev.taxAmount().add(tax),
                        prev.lineCount() + 1));
            }
        }
        return List.copyOf(grouped.values());
    }

    /**
     * 含税金额反算税额：税额 = 含税金额 × 税率 / (100 + 税率)，四舍五入保留 2 位小数。
     */
    public static BigDecimal taxIn(BigDecimal amount, BigDecimal rate) {
        if (amount == null || rate == null || rate.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return amount.multiply(rate).divide(BigDecimal.valueOf(100).add(rate), 2, RoundingMode.HALF_UP);
    }

    /**
     * 规范化账期天数（未填默认 30 天，允许范围 0~365 天）。
     */
    public static int normalizeCreditDays(Integer creditDays) {
        if (creditDays == null) {
            return DEFAULT_CREDIT_DAYS;
        }
        if (creditDays < 0 || creditDays > MAX_CREDIT_DAYS) {
            throw new IllegalArgumentException("账期天数须在 0 到 " + MAX_CREDIT_DAYS + " 天之间");
        }
        return creditDays;
    }

    /**
     * 计算对账单应付到期日：对账截止日 + 账期天数。
     */
    public static LocalDate computeDueDate(LocalDate periodTo, Integer creditDays) {
        if (periodTo == null) {
            return null;
        }
        return periodTo.plusDays(normalizeCreditDays(creditDays));
    }

    /**
     * 计算逾期天数（未逾期或已结清返回 0）。
     */
    public static long overdueDays(LocalDate dueDate, LocalDate today, boolean settledOrCancelled) {
        if (settledOrCancelled || dueDate == null || today == null || !today.isAfter(dueDate)) {
            return 0L;
        }
        return ChronoUnit.DAYS.between(dueDate, today);
    }

    /**
     * 校验多张发票（支持蓝字发票 BLUE 与红字发票 RED）是否与对账单合计严格一致。
     * 返回规范化后的发票净额合计；不一致或存在重复发票号时抛出 IllegalArgumentException。
     */
    public static BigDecimal validateInvoices(List<InvoiceInput> invoices, BigDecimal statementTotal, LocalDate today) {
        if (invoices == null || invoices.isEmpty()) {
            throw new IllegalArgumentException("请至少登记一张发票");
        }
        Set<String> seenNos = new HashSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (InvoiceInput inv : invoices) {
            if (inv.invoiceNo() == null || inv.invoiceNo().isBlank()) {
                throw new IllegalArgumentException("发票号不能为空");
            }
            String no = inv.invoiceNo().trim();
            if (!seenNos.add(no)) {
                throw new IllegalArgumentException("同一张对账单内发票号重复：" + no);
            }
            if (inv.invoiceDate() == null || (today != null && inv.invoiceDate().isAfter(today))) {
                throw new IllegalArgumentException("发票 " + no + " 的开票日期不能为空且不能晚于今天");
            }
            if (inv.amount() == null || inv.amount().signum() == 0) {
                throw new IllegalArgumentException("发票 " + no + " 的金额不能为 0");
            }
            String type = inv.invoiceType() == null || inv.invoiceType().isBlank() ? "BLUE" : inv.invoiceType().trim().toUpperCase();
            if (!"BLUE".equals(type) && !"RED".equals(type)) {
                throw new IllegalArgumentException("发票类型只能为蓝字(BLUE)或红字(RED)");
            }
            if ("BLUE".equals(type) && inv.amount().signum() < 0) {
                throw new IllegalArgumentException("蓝字发票 " + no + " 金额必须大于 0");
            }
            if ("RED".equals(type) && inv.amount().signum() > 0) {
                throw new IllegalArgumentException("红字发票 " + no + " 金额必须为负数");
            }
            sum = sum.add(inv.amount().setScale(2, RoundingMode.HALF_UP));
        }
        BigDecimal expected = statementTotal.setScale(2, RoundingMode.HALF_UP);
        if (sum.compareTo(expected) != 0) {
            throw new IllegalArgumentException("发票金额合计 " + sum.toPlainString() + " 与对账单合计 " + expected.toPlainString() + " 不一致");
        }
        return sum;
    }
}
