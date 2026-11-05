package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.master.ItemRepository;
import com.cosmetic.scm.master.PartnerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** 超出数据库精度或长度的输入要明确拒绝，不能被悄悄四舍五入，提示要让用户看得懂。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InputBoundaryTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ItemRepository items;
    @Autowired PartnerRepository partners;

    private String login(String user, String password) throws Exception {
        String resp = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", user, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("token").asText();
    }

    private String rejected(String token, String url, Object body) throws Exception {
        var res = mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))
                .header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest()).andReturn();
        return json.readTree(res.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("message").asText();
    }

    private Map<String, Object> po(Object qty) {
        Long sup = partners.findAll().stream().filter(p -> p.getKind() == PartnerKind.SUPPLIER && p.isQualified()).findFirst().orElseThrow().getId();
        Long gly = items.findAll().stream().filter(i -> "RM-GLY-002".equals(i.getCode())).findFirst().orElseThrow().getId();
        return Map.of("supplierId", sup, "lines", List.of(Map.of("itemId", gly, "qty", qty)));
    }

    @Test
    void quantitiesBeyondColumnPrecisionAreRejectedNotRounded() throws Exception {
        String t = login("purchase1", "Demo@12345");
        assertThat(rejected(t, "/api/purchase-orders", po(new java.math.BigDecimal("0.00001")))).isEqualTo("数量最多 14 位整数、4 位小数");
        assertThat(rejected(t, "/api/purchase-orders", po(new java.math.BigDecimal("1.23456")))).isEqualTo("数量最多 14 位整数、4 位小数");
        assertThat(rejected(t, "/api/purchase-orders", po(new java.math.BigDecimal("1E+15")))).isEqualTo("数量最多 14 位整数、4 位小数");
        // 恰好 4 位小数可以
        mvc.perform(post("/api/purchase-orders").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(po(new java.math.BigDecimal("1.2345"))))
                .header("Authorization", "Bearer " + t)).andExpect(status().isOk());
    }

    @Test
    void validationMessagesAreChineseAndNameTheField() throws Exception {
        String t = login("purchase1", "Demo@12345");
        assertThat(rejected(t, "/api/purchase-orders", po(0))).isEqualTo("第 1 行数量：要大于 0");
        String admin = login("admin", "Admin@123456");
        var item = new java.util.HashMap<String, Object>(Map.of("code", "FG-LONG", "itemType", "FG", "unit", "瓶", "banned", false, "shelfMonths", 36, "active", true));
        item.put("name", "测".repeat(101));
        assertThat(rejected(admin, "/api/items", item)).isEqualTo("名称：长度要在 0 到 100 之间");
        // 备案编号要写进备案表（60 位），校验不能比列宽
        item.put("name", "甲");
        item.put("filingNo", "测".repeat(61));
        assertThat(rejected(admin, "/api/items", item)).isEqualTo("备案编号：长度要在 0 到 60 之间");
        item.put("filingNo", null);
        item.put("stdCost", new java.math.BigDecimal("1E+20"));
        assertThat(rejected(admin, "/api/items", item)).contains("标准成本最多");
    }

    private int loginFrom(String ip, String xff, String user, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", user, "password", password)))
                        .header("X-Real-IP", ip).header("X-Forwarded-For", xff))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void tooManyFailedLoginsFromOneSourceAreThrottled() throws Exception {
        String ip = "203.0.113." + System.nanoTime() % 200;
        for (int i = 0; i < 20; i++) {
            // 换账号试、换 X-Forwarded-For 都没用，nginx 会覆盖 X-Real-IP
            assertThat(loginFrom(ip, "9.9.9." + i, "spray" + i, "wrong")).isEqualTo(401);
        }
        assertThat(loginFrom(ip, "8.8.8.8", "spray-x", "wrong")).isEqualTo(429);
        // 这一分钟里这个来源连正确密码也进不了，别的来源不受影响
        assertThat(loginFrom(ip, "8.8.8.8", "qa1", "Demo@12345")).isEqualTo(429);
        assertThat(loginFrom("198.51.100.7", "8.8.8.8", "qa1", "Demo@12345")).isEqualTo(200);
    }
}
