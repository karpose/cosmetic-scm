package com.cosmetic.scm.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class FefoAllocatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);

    private static final List<FefoAllocator.Candidate> STOCK = List.of(
            new FefoAllocator.Candidate(1L, "B-NEW", 1L, new BigDecimal("3"), LocalDate.of(2026, 9, 5), LocalDate.of(2029, 9, 4)),
            new FefoAllocator.Candidate(2L, "B-OLD", 1L, new BigDecimal("0.2"), LocalDate.of(2023, 12, 1), LocalDate.of(2026, 11, 30)),
            new FefoAllocator.Candidate(3L, "B-EXP", 1L, new BigDecimal("9"), LocalDate.of(2023, 1, 1), LocalDate.of(2026, 1, 1)));

    @Test
    void picksEarliestExpiryFirstAndSkipsExpired() {
        FefoAllocator.Result r = FefoAllocator.allocate(STOCK, new BigDecimal("3.2"), 0, TODAY);
        assertThat(r.picks()).extracting(FefoAllocator.Pick::batchNo).containsExactly("B-OLD", "B-NEW");
        assertThat(r.picks().get(1).qty()).isEqualByComparingTo("3");
        assertThat(r.fulfilled()).isTrue();
        assertThat(r.excludedQty()).isEqualByComparingTo("9");
    }

    @Test
    void customerShelfLifeThresholdExcludesNearExpiry() {
        FefoAllocator.Result r = FefoAllocator.allocate(STOCK, BigDecimal.ONE, 0.67, TODAY);
        assertThat(r.picks()).extracting(FefoAllocator.Pick::batchNo).containsExactly("B-NEW");
        assertThat(r.excludedQty()).isEqualByComparingTo("9.2");
    }

    @Test
    void reportsShortage() {
        FefoAllocator.Result r = FefoAllocator.allocate(STOCK, BigDecimal.TEN, 0, TODAY);
        assertThat(r.fulfilled()).isFalse();
        assertThat(r.shortQty()).isEqualByComparingTo("6.8");
    }

    @Test
    void undatedBatchesComeLast() {
        List<FefoAllocator.Candidate> c = List.of(
                new FefoAllocator.Candidate(5L, "P-2", 1L, new BigDecimal("5"), null, null),
                new FefoAllocator.Candidate(4L, "P-1", 1L, new BigDecimal("5"), LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)));
        assertThat(FefoAllocator.allocate(c, new BigDecimal("6"), 0, TODAY).picks().get(0).batchNo()).isEqualTo("P-1");
    }

    @Test
    void rejectsNonPositiveQuantity() {
        assertThatThrownBy(() -> FefoAllocator.allocate(STOCK, BigDecimal.ZERO, 0, TODAY))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
