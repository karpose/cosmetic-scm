package com.cosmetic.scm.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DeploymentSecretsCheckTest {

    @Test
    void placeholdersFromEnvExampleAreRejected() {
        assertThat(DeploymentSecretsCheck.problems("请改成至少32位的随机字符串", "请改成强密码", "请改成初始管理员密码"))
                .containsExactly("SCM_JWT_SECRET", "SCM_DB_PASSWORD", "SCM_ADMIN_PASSWORD");
    }

    @Test
    void developmentDefaultsAreRejected() {
        assertThat(DeploymentSecretsCheck.problems("dev-only-secret-please-change-0123456789ab", "x9", "Admin@123456"))
                .containsExactly("SCM_JWT_SECRET", "SCM_ADMIN_PASSWORD");
    }

    @Test
    void realValuesPass() {
        assertThat(DeploymentSecretsCheck.problems("k3j4h5k3j4h5k3j4h5k3j4h5k3j4h5k3j4h5", "db-Pw-9", "Adm1n#Strong")).isEmpty();
    }
}
