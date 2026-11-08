package com.cosmetic.scm.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CasNumberTest {

    @Test
    void checkDigitAndFormat() {
        // 水 7732-18-5：8×1+1×2+2×3+3×4+7×5+7×6 = 105，个位 5
        assertThat(CasNumber.normalize("7732-18-5")).contains("7732-18-5");
        assertThat(CasNumber.normalize(" ７７３２－１８－５ ")).contains("7732-18-5");
        assertThat(CasNumber.normalize("7732-18-4")).isEmpty();
        assertThat(CasNumber.normalize("7732185")).isEmpty();
        assertThat(CasNumber.normalize("1-18-5")).isEmpty();
        assertThat(CasNumber.normalize(null)).isEmpty();
    }

    @Test
    void parseAllKeepsOnlyValidNumbers() {
        assertThat(CasNumber.parseAll("7732-18-5； 7732-18-4 / 无 、7732-18-5")).containsExactly("7732-18-5");
        assertThat(CasNumber.parseAll("—")).isEmpty();
        assertThat(CasNumber.parseAll(null)).isEmpty();
    }
}
