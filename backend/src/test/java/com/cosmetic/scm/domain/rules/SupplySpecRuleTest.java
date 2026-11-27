package com.cosmetic.scm.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.cosmetic.scm.domain.rules.SupplySpecRule.State;

class SupplySpecRuleTest {

    @Test
    void noSpecWhenNoVersionRequired() {
        assertThat(SupplySpecRule.state(null, List.of(1, 2))).isEqualTo(State.NO_SPEC);
        assertThat(State.NO_SPEC.awaitingAck()).isFalse();
    }

    @Test
    void unackedWhenFactoryNeverConfirmed() {
        assertThat(SupplySpecRule.state(1, List.of())).isEqualTo(State.UNACKED);
        assertThat(SupplySpecRule.state(1, null)).isEqualTo(State.UNACKED);
        assertThat(State.UNACKED.awaitingAck()).isTrue();
    }

    @Test
    void olderVersionIsOutdatedAndRequiredOrNewerIsAcked() {
        assertThat(SupplySpecRule.state(3, List.of(1, 2))).isEqualTo(State.OUTDATED);
        assertThat(State.OUTDATED.awaitingAck()).isTrue();
        assertThat(SupplySpecRule.state(3, List.of(1, 3))).isEqualTo(State.ACKED);
        // 自查看期间结束时的版本：之后换了版、受托厂确认了新版，也算确认过
        assertThat(SupplySpecRule.state(2, List.of(3))).isEqualTo(State.ACKED);
        assertThat(State.ACKED.awaitingAck()).isFalse();
    }

    @Test
    void rawMaterialNeedsInci() {
        assertThat(SupplySpecRule.compositionMissing(true, null)).isTrue();
        assertThat(SupplySpecRule.compositionMissing(true, "  ")).isTrue();
        assertThat(SupplySpecRule.compositionMissing(true, "Niacinamide")).isFalse();
        assertThat(SupplySpecRule.compositionMissing(false, null)).isFalse();
    }
}
