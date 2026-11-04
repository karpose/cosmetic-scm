package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.EnumSet;

import com.cosmetic.scm.domain.Role;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 走真实的登录和令牌。会改账号，用独立的内存库。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-token;MODE=MySQL;DB_CLOSE_DELAY=-1")
class TokenSecurityTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;

    private String login(String user, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    @Test
    void disabledAccountLosesAccessImmediately() throws Exception {
        String token = login("viewer1", "Demo@12345");
        mvc.perform(get("/api/stock").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        AppUser u = users.findByUsername("viewer1").orElseThrow();
        u.setEnabled(false);
        users.save(u);
        mvc.perform(get("/api/stock").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        u.setEnabled(true);
        users.save(u);
    }

    @Test
    void roleChangesApplyWithoutNewLogin() throws Exception {
        String token = login("purchase1", "Demo@12345");
        String partners = "/api/purchase-orders";
        mvc.perform(post(partners).header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"supplierId\":999999,\"lines\":[{\"itemId\":1,\"qty\":1}]}")).andExpect(status().isNotFound());
        AppUser u = users.findByUsername("purchase1").orElseThrow();
        u.setRoles(EnumSet.of(Role.VIEWER));
        users.save(u);
        mvc.perform(post(partners).header("Authorization", "Bearer " + token).contentType("application/json")
                .content("{\"supplierId\":999999,\"lines\":[{\"itemId\":1,\"qty\":1}]}")).andExpect(status().isForbidden());
    }

    @Test
    void changingPasswordInvalidatesOldTokensAndIssuesNewOne() throws Exception {
        String old = login("sales1", "Demo@12345");
        // 令牌的签发时间只到秒：等过这一秒，才能和改密时间区分开
        Thread.sleep(1100);
        String body = mvc.perform(post("/api/auth/password").header("Authorization", "Bearer " + old).contentType("application/json")
                        .content("{\"oldPassword\":\"Demo@12345\",\"newPassword\":\"Changed@67890\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String fresh = json.readTree(body).get("token").asText();
        assertThat(fresh).isNotBlank().isNotEqualTo(old);
        // 存的是整秒：MySQL 的 DATETIME 会四舍五入小数秒，否则新令牌有一半概率被判成旧令牌（H2 上测不出来）
        assertThat(users.findByUsername("sales1").orElseThrow().getPasswordChangedAt().getNano()).isZero();
        mvc.perform(get("/api/stock").header("Authorization", "Bearer " + old)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/stock").header("Authorization", "Bearer " + fresh)).andExpect(status().isOk());
    }

    @Test
    void adminCannotChangeOwnRoles() throws Exception {
        String token = login("admin", "Admin@123456");
        AppUser me = users.findByUsername("admin").orElseThrow();
        String roles = String.join("\",\"", me.getRoles().stream().map(Enum::name).toList());
        mvc.perform(put("/api/users/" + me.getId()).header("Authorization", "Bearer " + token).contentType("application/json")
                        .content("{\"displayName\":\"系统管理员\",\"enabled\":true,\"roles\":[\"" + roles + "\",\"QSO\"]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("不能修改自己的角色，请让另一位管理员操作"));
        // 不改角色的编辑不受影响
        mvc.perform(put("/api/users/" + me.getId()).header("Authorization", "Bearer " + token).contentType("application/json")
                        .content("{\"displayName\":\"系统管理员\",\"enabled\":true,\"roles\":[\"" + roles + "\"]}"))
                .andExpect(status().isOk());
    }

    @Test
    void spoofedForwardedHeaderCannotDodgeScanRateLimit() throws Exception {
        int last = 0;
        for (int i = 0; i < 40; i++) {
            last = mvc.perform(post("/api/public/verify").contentType("application/json").content("{\"code\":\"0000-0000-0000\"}")
                            .header("X-Real-IP", "203.0.113.9").header("X-Forwarded-For", "10.0.0." + i))
                    .andReturn().getResponse().getStatus();
        }
        assertThat(last).isEqualTo(429);
    }
}
