package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 质量安全负责人任职档案和协助履职授权。现任会影响总览、上线检查和自查核对，用单独的库。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-qso;MODE=MySQL;DB_CLOSE_DELAY=-1")
class QsoAppointmentTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;
    private static final String QSO2_PWD = "Lamp#Rv2026x";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TimeProvider time;

    private String login(String u, String p) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", u, "password", p)))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    private ResultActions call(String t, String url, Object body) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private JsonNode ok(ResultActions r) throws Exception {
        return json.readTree(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode read(String t, String url) throws Exception {
        return ok(mvc.perform(get(url).header("Authorization", "Bearer " + t)));
    }

    private void upload(String t, long id) throws Exception {
        mvc.perform(multipart("/api/attachments").file(new MockMultipartFile("file", "任命书.pdf", "application/pdf", "%PDF-1.4 appoint".getBytes(StandardCharsets.UTF_8)))
                .param("ownerType", "QSO_APPOINTMENT").param("ownerId", String.valueOf(id)).header("Authorization", "Bearer " + t)).andExpect(status().isOk());
    }

    private Map<String, Object> appoint(String user, int years) {
        return new HashMap<>(Map.of("username", user, "personName", "姓名" + user, "majorField", "CHEMISTRY", "education", "本科", "yearsExperience", years,
                "experience", "化妆品企业质量管理", "appointedOn", time.today().toString()));
    }

    private Map<String, Object> delegate(String user, List<String> duties, LocalDate from, LocalDate to, String password) {
        return Map.of("delegateUsername", user, "duties", duties, "scope", "协助成品上市放行的记录审核", "validFrom", from.toString(), "validTo", to.toString(),
                "password", password);
    }

    private JsonNode userCheck(JsonNode overview, String user) {
        for (JsonNode u : overview.get("qsoUsers")) {
            if (u.get("username").asText().equals(user)) {
                return u;
            }
        }
        throw new AssertionError(user);
    }

    private String todos(String t) throws Exception {
        return read(t, "/api/dashboard").get("todos").toString();
    }

    @Test
    void appointmentNeedsQualificationAndFilesAndDelegationsFollowTheCurrentQso() throws Exception {
        String admin = login("admin", "Admin@123456");
        String qa = login("qa1", PWD);
        String qso1 = login("qso1", PWD);
        LocalDate today = time.today();

        JsonNode o = read(qa, "/api/qso");
        assertThat(o.get("current").isNull()).isTrue();
        assertThat(userCheck(o, "qso1").get("ok").asBoolean()).isFalse();
        // 还没登记现任：总览不提醒，上线检查提示
        assertThat(todos(admin)).doesNotContain("质量安全负责人角色没有任职或授权");
        assertThat(read(admin, "/api/golive").toString()).contains("还没有登记生效的质量安全负责人任职档案");

        // 登记：只给管理员；要有质量安全负责人角色；经验至少 5 年；其他专业要写明；任职日期不能晚于今天
        call(qa, "/api/qso/appointments", appoint("qso1", 8)).andExpect(status().isForbidden());
        call(admin, "/api/qso/appointments", appoint("qa1", 8)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("没有质量安全负责人角色")));
        call(admin, "/api/qso/appointments", appoint("qso1", 4)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("5 年以上")));
        Map<String, Object> other = appoint("qso1", 8);
        other.put("majorField", "OTHER");
        call(admin, "/api/qso/appointments", other).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("写明专业名称")));
        Map<String, Object> future = appoint("qso1", 8);
        future.put("appointedOn", today.plusDays(1).toString());
        call(admin, "/api/qso/appointments", future).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不能晚于今天")));
        JsonNode a1 = ok(call(admin, "/api/qso/appointments", appoint("qso1", 8)));
        assertThat(a1.get("status").asText()).isEqualTo("DRAFT");
        long a1id = a1.get("id").asLong();

        // 生效：要有任命文件，要密码
        call(admin, "/api/qso/appointments/" + a1id + "/activate", Map.of("password", "Admin@123456")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("任命文件")));
        upload(admin, a1id);
        call(admin, "/api/qso/appointments/" + a1id + "/activate", Map.of("password", "wrong")).andExpect(status().isBadRequest());
        ok(call(admin, "/api/qso/appointments/" + a1id + "/activate", Map.of("password", "Admin@123456")));
        o = read(qa, "/api/qso");
        assertThat(o.get("current").get("username").asText()).isEqualTo("qso1");
        assertThat(userCheck(o, "qso1").get("ok").asBoolean()).isTrue();
        assertThat(read(admin, "/api/golive").toString()).contains("质量安全负责人任职").contains("姓名qso1");

        // 第二个有质量安全负责人角色的账号：没有授权，总览提醒
        call(admin, "/api/users", Map.of("username", "qso2", "displayName", "质量副经理", "password", QSO2_PWD, "roles", List.of("QSO", "QA"))).andExpect(status().isOk());
        String qso2 = login("qso2", QSO2_PWD);
        assertThat(userCheck(read(qa, "/api/qso"), "qso2").get("ok").asBoolean()).isFalse();
        assertThat(todos(admin)).contains("质量安全负责人角色没有任职或授权").contains("qso2");

        // 授权：只有现任能办，不能给自己，期限最长 12 个月，要选职责
        call(qa, "/api/qso/delegations", delegate("qso2", List.of("RELEASE"), today, today.plusMonths(6), PWD)).andExpect(status().isForbidden());
        call(qso2, "/api/qso/delegations", delegate("qa1", List.of("RELEASE"), today, today.plusMonths(6), QSO2_PWD)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("只有现任质量安全负责人")));
        call(qso1, "/api/qso/delegations", delegate("qso1", List.of("RELEASE"), today, today.plusMonths(6), PWD)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("不能授权给自己")));
        call(qso1, "/api/qso/delegations", delegate("qso2", List.of("RELEASE"), today, today.plusMonths(13), PWD)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("最长 12 个月")));
        call(qso1, "/api/qso/delegations", delegate("qso2", List.of(), today, today.plusMonths(6), PWD)).andExpect(status().isBadRequest());
        call(qso1, "/api/qso/delegations", delegate("qso2", List.of("RELEASE"), today.plusDays(5), today, PWD)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("开始日期不能晚于结束日期")));
        JsonNode d1 = ok(call(qso1, "/api/qso/delegations", delegate("qso2", List.of("RELEASE"), today, today.plusMonths(6), PWD)));
        assertThat(d1.get("state").asText()).isEqualTo("IN_EFFECT");
        assertThat(d1.get("dutyLabels").asText()).contains("物料放行和产品放行");
        assertThat(userCheck(read(qa, "/api/qso"), "qso2").get("ok").asBoolean()).isTrue();
        assertThat(todos(admin)).doesNotContain("质量安全负责人角色没有任职或授权");

        // 监督记录：只有现任能记
        call(qso2, "/api/qso/delegations/" + d1.get("id").asLong() + "/reviews", Map.of("note", "x")).andExpect(status().isBadRequest());
        JsonNode reviewed = ok(call(qso1, "/api/qso/delegations/" + d1.get("id").asLong() + "/reviews", Map.of("note", "抽查 3 批放行记录，审核到位")));
        assertThat(reviewed.get("reviews").size()).isEqualTo(1);

        // 快到期的授权进总览
        JsonNode d2 = ok(call(qso1, "/api/qso/delegations", delegate("qa1", List.of("ADVERSE"), today, today.plusDays(10), PWD)));
        assertThat(d2.get("expiringSoon").asBoolean()).isTrue();
        assertThat(todos(qso1)).contains("协助履职授权快到期").contains(d2.get("delegationNo").asText());

        // 自查第 4、5 项的系统核对
        JsonNode check = ok(call(qa, "/api/qms/self-checks", Map.of("periodFrom", today.minusMonths(6).toString(), "periodTo", today.toString())));
        JsonNode ev = read(qa, "/api/qms/self-checks/" + check.get("check").get("id").asLong() + "/evidence");
        Map<Integer, JsonNode> byNo = new HashMap<>();
        ev.forEach(e -> byNo.put(e.get("itemNo").asInt(), e));
        assertThat(byNo.get(4).get("details").toString()).contains("姓名qso1").contains("经验 8 年");
        assertThat(byNo.get(5).get("summary").asText()).contains("生效中的协助履职授权 2 份");
        assertThat(byNo.get(5).get("details").toString()).contains(d1.get("delegationNo").asText()).contains("最近监督");

        // 撤销
        ok(call(qso1, "/api/qso/delegations/" + d2.get("id").asLong() + "/revoke", Map.of("note", "工作调整")));
        call(qso1, "/api/qso/delegations/" + d2.get("id").asLong() + "/revoke", Map.of("note", "再撤销")).andExpect(status().isBadRequest());

        // 换人：qso2 接任，qso1 离任，qso1 给出的授权一并撤销；qso1 还留着角色就没有授权了
        long a2id = ok(call(admin, "/api/qso/appointments", appoint("qso2", 6))).get("id").asLong();
        upload(admin, a2id);
        ok(call(admin, "/api/qso/appointments/" + a2id + "/activate", Map.of("password", "Admin@123456")));
        o = read(qa, "/api/qso");
        assertThat(o.get("current").get("username").asText()).isEqualTo("qso2");
        JsonNode old = null;
        for (JsonNode a : o.get("appointments")) {
            if (a.get("id").asLong() == a1id) {
                old = a;
            }
        }
        assertThat(old.get("status").asText()).isEqualTo("ENDED");
        assertThat(old.get("endReason").asText()).contains("接任");
        for (JsonNode d : o.get("delegations")) {
            assertThat(d.get("state").asText()).isEqualTo("REVOKED");
        }
        assertThat(userCheck(o, "qso1").get("ok").asBoolean()).isFalse();
        assertThat(todos(admin)).contains("质量安全负责人角色没有任职或授权").contains("qso1");

        // 离任：管理员写原因
        call(admin, "/api/qso/appointments/" + a2id + "/end", Map.of("note", " ")).andExpect(status().isBadRequest());
        ok(call(admin, "/api/qso/appointments/" + a2id + "/end", Map.of("note", "调岗")));
        assertThat(read(qa, "/api/qso").get("current").isNull()).isTrue();
        call(qso2, "/api/qso/delegations", delegate("qa1", List.of("REVIEW"), today, today.plusMonths(1), QSO2_PWD)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("还没有生效的质量安全负责人任职")));
        assertThat(read(admin, "/api/audit?q=" + a1.get("appointNo").asText()).toString()).contains("登记质量安全负责人任职").contains("任职生效").contains("离任");
    }
}
