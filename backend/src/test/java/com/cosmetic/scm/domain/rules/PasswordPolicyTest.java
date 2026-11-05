package com.cosmetic.scm.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    @Test
    void acceptsThreeCharacterClasses() {
        assertThat(PasswordPolicy.check("Demo@12345", "qa1")).isNull();
        assertThat(PasswordPolicy.check("lower123!", "qa1")).isNull();
    }

    @Test
    void rejectsShortOrSimplePasswords() {
        assertThat(PasswordPolicy.check("Ab1!", "qa1")).contains("至少 8 位");
        assertThat(PasswordPolicy.check("abcdefgh123", "qa1")).contains("至少 3 类");
    }

    @Test
    void rejectsPasswordContainingUsername() {
        assertThat(PasswordPolicy.check("Sales1@2026", "sales1")).contains("账号名");
    }

    @Test
    void passwordsOverBcryptByteLimitAreRejectedNotCrashed() {
        // 30 个汉字没超过字符上限，但有 90 个字节
        assertThat(PasswordPolicy.check("密".repeat(30) + "Aa1!", "someone")).contains("字节");
        assertThat(PasswordPolicy.check("Aa1!".repeat(16), "someone")).isNull();
    }
}
