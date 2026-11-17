package com.cosmetic.scm.domain.rules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.cosmetic.scm.domain.rules.SettlementRule.InvoiceInput;
import com.cosmetic.scm.domain.rules.SettlementRule.TaxBreakdown;
import com.cosmetic.scm.domain.rules.SettlementRule.TaxLineInput;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SettlementRuleTest {

    @Test
    void breakdownByTaxRateGroupsAndSortsRates() {
        List<TaxBreakdown> res = SettlementRule.breakdownByTaxRate(List.of(
                new TaxLineInput(new BigDecimal("13.00"), new BigDecimal("1130.00"), new BigDecimal("130.00")),
                new TaxLineInput(new BigDecimal("6.00"), new BigDecimal("106.00"), new BigDecimal("6.00")),
                new TaxLineInput(new BigDecimal("13.00"), new BigDecimal("-226.00"), new BigDecimal("-26.00"))
        ));
        assertEquals(2, res.size());
        assertEquals(new BigDecimal("6.00"), res.get(0).taxRate());
        assertEquals(new BigDecimal("100.00"), res.get(0).netAmount());
        assertEquals(new BigDecimal("13.00"), res.get(1).taxRate());
        assertEquals(new BigDecimal("904.00"), res.get(1).goodsAmount());
        assertEquals(new BigDecimal("800.00"), res.get(1).netAmount());
        assertEquals(new BigDecimal("104.00"), res.get(1).taxAmount());
        assertEquals(2, res.get(1).lineCount());
    }

    @Test
    void dueDateAndOverdueDaysCalculation() {
        LocalDate periodTo = LocalDate.of(2026, 10, 31);
        LocalDate due = SettlementRule.computeDueDate(periodTo, 30);
        assertEquals(LocalDate.of(2026, 11, 30), due);
        assertEquals(0L, SettlementRule.overdueDays(due, LocalDate.of(2026, 11, 30), false));
        assertEquals(5L, SettlementRule.overdueDays(due, LocalDate.of(2026, 12, 5), false));
        assertEquals(0L, SettlementRule.overdueDays(due, LocalDate.of(2026, 12, 5), true));
    }

    @Test
    void validateMultiBlueAndRedInvoices() {
        LocalDate today = LocalDate.of(2026, 11, 16);
        BigDecimal sum = SettlementRule.validateInvoices(List.of(
                new InvoiceInput("INV-001", "BLUE", today, new BigDecimal("13.00"), new BigDecimal("1000.00")),
                new InvoiceInput("INV-002-RED", "RED", today, new BigDecimal("13.00"), new BigDecimal("-150.00"))
        ), new BigDecimal("850.00"), today);
        assertEquals(new BigDecimal("850.00"), sum);

        assertThrows(IllegalArgumentException.class, () -> SettlementRule.validateInvoices(List.of(
                new InvoiceInput("INV-001", "BLUE", today, new BigDecimal("13.00"), new BigDecimal("800.00"))
        ), new BigDecimal("850.00"), today));
    }
}
