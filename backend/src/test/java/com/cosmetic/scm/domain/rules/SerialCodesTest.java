package com.cosmetic.scm.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SerialCodesTest {

    @Test
    void randomCodesAreWellFormedAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            String c = SerialCodes.random();
            assertThat(SerialCodes.wellFormed(c)).isTrue();
            assertThat(seen.add(c)).isTrue();
        }
    }

    @Test
    void normalizeToleratesFormattingAndLookalikes() {
        assertThat(SerialCodes.normalize(" 7k3m-q9tx-2b8o ")).isEqualTo("7K3MQ9TX2B80");
        assertThat(SerialCodes.display("7K3MQ9TX2B80")).isEqualTo("7K3M-Q9TX-2B80");
        assertThat(SerialCodes.wellFormed("7K3MQ9TX2B8U")).isFalse();
        assertThat(SerialCodes.wellFormed("SHORT")).isFalse();
    }
}
