package com.cosmetic.scm.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.cosmetic.scm.domain.DrillResult;
import com.cosmetic.scm.domain.DrillTarget;

import org.junit.jupiter.api.Test;

class RecallDrillRuleTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 20, 9, 0, 0);

    private static RecallDrillRule.Line customer(int seq, String qty, DrillResult r, String held, int minutes) {
        return new RecallDrillRule.Line(seq, DrillTarget.CUSTOMER, "客户" + seq, new BigDecimal(qty), r, held == null ? null : new BigDecimal(held),
                r == null ? null : START.plusMinutes(minutes));
    }

    private static RecallDrillRule.Line warehouse(int seq, String qty, String counted, int minutes) {
        return new RecallDrillRule.Line(seq, DrillTarget.WAREHOUSE, "主仓", new BigDecimal(qty), DrillResult.REACHED, new BigDecimal(counted),
                START.plusMinutes(minutes));
    }

    @Test
    void metWhenEveryoneReachedStockMatchesAndWithinTarget() {
        RecallDrillRule.Outcome o = RecallDrillRule.evaluate(START, 24, List.of(
                customer(1, "2000", DrillResult.REACHED, "300", 50),
                customer(2, "1000", DrillResult.REACHED, null, 125),
                new RecallDrillRule.Line(3, DrillTarget.FACTORY, "禾川", null, DrillResult.REACHED, null, START.plusMinutes(30)),
                warehouse(4, "6930", "6930", 90)));
        assertThat(o.met()).isTrue();
        assertThat(o.gaps()).isEmpty();
        // 用时按最后一家联系完算
        assertThat(o.finishedAt()).isEqualTo(START.plusMinutes(125));
        assertThat(o.elapsedMinutes()).isEqualTo(125);
        assertThat(RecallDrillRule.hours(o.elapsedMinutes())).isEqualTo("2.1");
        assertThat(o.locatedPct()).isEqualByComparingTo("100");
    }

    @Test
    void gapsForUnreachableStockMismatchTooMuchHeldAndOvertime() {
        RecallDrillRule.Outcome o = RecallDrillRule.evaluate(START, 2, List.of(
                customer(1, "2000", DrillResult.REACHED, "2100", 10),
                customer(2, "1000", DrillResult.UNREACHABLE, null, 200),
                warehouse(3, "500", "480", 20)));
        assertThat(o.met()).isFalse();
        assertThat(o.gaps()).containsExactly(
                "收货客户 客户1 反馈在手 2100，多于在外数量 2000，请核对出库和退货记录",
                "收货客户 客户2 联系不上",
                "仓库 主仓 实盘 480，账面 500",
                "用时 3.3 小时，超过目标 2 小时");
        // 只有联系上的客户算定位：2000 / 3000，向下取一位小数
        assertThat(o.locatedPct()).isEqualByComparingTo("66.6");
    }

    @Test
    void unrecordedLinesAreListedAndExactlyOnTargetIsMet() {
        List<RecallDrillRule.Line> lines = List.of(customer(1, "10", DrillResult.REACHED, null, 60), customer(2, "5", null, null, 0));
        assertThat(RecallDrillRule.unrecorded(lines)).containsExactly("收货客户 客户2 还没有登记联系结果");
        RecallDrillRule.Outcome o = RecallDrillRule.evaluate(START, 1, lines);
        assertThat(o.met()).isFalse();
        assertThat(o.locatedPct()).isEqualByComparingTo("66.6");
        // 正好等于目标用时算达标
        assertThat(RecallDrillRule.evaluate(START, 1, List.of(customer(1, "10", DrillResult.REACHED, null, 60))).met()).isTrue();
        // 没有一家登记过：没有用时
        assertThat(RecallDrillRule.evaluate(START, 1, List.of(customer(1, "10", null, null, 0))).elapsedMinutes()).isNull();
    }

    @Test
    void dueOnlyAfterShipmentsAndWhenLastDrillIsOlderThanInterval() {
        LocalDate today = LocalDate.of(2026, 11, 26);
        assertThat(RecallDrillRule.due(false, null, today, 12)).isFalse();
        assertThat(RecallDrillRule.due(true, null, today, 12)).isTrue();
        assertThat(RecallDrillRule.due(true, today.minusMonths(12), today, 12)).isFalse();
        assertThat(RecallDrillRule.due(true, today.minusMonths(12).minusDays(1), today, 12)).isTrue();
    }
}
