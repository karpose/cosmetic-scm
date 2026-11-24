package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** GMP 自查（检查要点委托生产版）。进行中的自查会影响总览和上线检查，用单独的库。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-selfcheck;MODE=MySQL;DB_CLOSE_DELAY=-1")
class GmpSelfCheckTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;
    private static final String BASE = "/api/qms/self-checks";

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

    private static Map<String, Object> item(String result, String finding) {
        Map<String, Object> m = new HashMap<>(Map.of("result", result));
        if (finding != null) {
            m.put("finding", finding);
        }
        return m;
    }

    private Map<String, Object> capa(LocalDate due) {
        return Map.of("actionType", "CORRECTIVE", "description", "补齐", "owner", "qa1", "dueDate", due.toString());
    }

    private JsonNode itemOf(JsonNode d, int no) {
        return d.get("items").get(no - 1);
    }

    @Test
    void selfCheckFollowsTheChecklistJudgementAndNeedsCapaAndAnotherQso() throws Exception {
        String qa = login("qa1", PWD);
        String qso = login("qso1", PWD);
        LocalDate today = time.today();

        // 24 项：关键 1、其他重点 8、一般 15；第 13 项是关键项目
        JsonNode list = read(qa, BASE + "/checklist");
        assertThat(list.size()).isEqualTo(24);
        Map<String, Integer> levels = new HashMap<>();
        list.forEach(i -> levels.merge(i.get("level").asText(), 1, Integer::sum));
        assertThat(levels).containsEntry("KEY", 1).containsEntry("IMPORTANT", 8).containsEntry("GENERAL", 15);
        assertThat(list.get(12).get("level").asText()).isEqualTo("KEY");

        // 发起：权限、期间
        Map<String, Object> period = Map.of("periodFrom", today.minusYears(1).plusDays(1).toString(), "periodTo", today.toString(), "scope", "全部委托生产产品");
        call(login("prod1", PWD), BASE, period).andExpect(status().isForbidden());
        call(qa, BASE, Map.of("periodFrom", today.toString(), "periodTo", today.minusDays(1).toString())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("开始日期不能晚于结束日期")));
        call(qa, BASE, Map.of("periodFrom", today.toString(), "periodTo", today.plusDays(1).toString())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("不能晚于今天")));
        call(qa, BASE, Map.of("periodFrom", today.minusYears(2).toString(), "periodTo", today.toString())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("最长一年")));
        JsonNode d = ok(call(qa, BASE, period));
        long id = d.get("check").get("id").asLong();
        String url = BASE + "/" + id;
        String no = d.get("check").get("checkNo").asText();
        assertThat(d.get("items").size()).isEqualTo(24);
        assertThat(d.get("check").get("counts").get("pending").asInt()).isEqualTo(24);
        call(qso, BASE, period).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("已有一份自查在进行")));

        // 自动核对：示例数据里有一件还没上报的不良反应
        JsonNode ev = read(qa, url + "/evidence");
        Map<Integer, JsonNode> byNo = new HashMap<>();
        ev.forEach(e -> byNo.put(e.get("itemNo").asInt(), e));
        assertThat(byNo.keySet()).contains(2, 3, 4, 5, 13, 16, 18, 19, 23, 24).doesNotContain(1, 8);
        // 制度覆盖：还没有文件标注“质量安全责任制”
        assertThat(byNo.get(3).get("level").asText()).isEqualTo("WARN");
        assertThat(byNo.get(3).get("details").toString()).contains("质量安全责任制").contains("还没有对应的受控文件");
        // 只缺提供物料时才要的制度（示例 SOP 标了物料放行和进货查验，还没生效），不算要注意
        assertThat(byNo.get(12).get("details").toString()).contains("提供物料时才要");
        assertThat(byNo.get(23).get("level").asText()).isEqualTo("WARN");
        assertThat(byNo.get(23).get("summary").asText()).contains("还没上报");
        assertThat(byNo.get(4).get("summary").asText()).contains("质量安全负责人账号");

        // 登记规则
        call(qa, url + "/items/1", item("FLAWED", "x")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("一般项目")));
        call(qa, url + "/items/2", item("NOT_APPLICABLE", "x")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不能判不适用")));
        call(qa, url + "/items/12", item("NOT_APPLICABLE", " ")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("写明原因")));
        call(qa, url + "/items/20", item("NONCOMPLIANT", null)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("写明发现的问题")));
        call(qa, url + "/items/3", item("PENDING", null)).andExpect(status().isBadRequest());
        call(qa, url + "/items/25", item("COMPLIANT", null)).andExpect(status().isNotFound());
        call(login("viewer1", PWD), url + "/items/3", item("COMPLIANT", null)).andExpect(status().isForbidden());

        for (int i = 1; i <= 24; i++) {
            if (i != 12 && i != 19 && i != 20) {
                ok(call(qa, url + "/items/" + i, item("COMPLIANT", null)));
            }
        }
        ok(call(qa, url + "/items/12", item("NOT_APPLICABLE", "不向受托方提供物料")));
        ok(call(qa, url + "/items/19", item("FLAWED", "两个产品没有维护净含量")));
        d = ok(call(qa, url + "/items/20", item("NONCOMPLIANT", "过期产品没有定期检查")));
        assertThat(d.get("check").get("conclusion").asText()).isEqualTo("DEFECT");
        JsonNode counts = d.get("check").get("counts");
        assertThat(counts.get("pending").asInt()).isZero();
        assertThat(counts.get("flawed").asInt()).isEqualTo(1);
        assertThat(counts.get("noncompliant").asInt()).isEqualTo(1);
        assertThat(counts.get("notApplicable").asInt()).isEqualTo(1);
        assertThat(counts.get("rule3").asInt()).isEqualTo(2);

        // 瑕疵和不符合都要先转 CAPA
        call(qa, url + "/submit", Map.of("summary", "年度自查")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("第 19、20 项")));
        call(qa, url + "/items/1/capa", capa(today.plusDays(30))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不用转 CAPA")));
        call(qa, url + "/items/20/capa", capa(today.minusDays(1))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不能早于今天")));
        d = ok(call(qa, url + "/items/19/capa", capa(today.plusDays(30))));
        String capaNo = itemOf(d, 19).get("capaNo").asText();
        assertThat(capaNo).startsWith("CA");
        call(qa, url + "/items/19/capa", capa(today.plusDays(30))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("已经转过")));
        ok(call(qa, url + "/items/20/capa", capa(today.plusDays(30))));
        assertThat(read(qa, "/api/qms/capas").toString()).contains(capaNo).contains("GMP 自查").contains(no + " 第19项");

        // 提交：要写总结；提交后不能再登记
        call(qa, url + "/submit", Map.of("summary", " ")).andExpect(status().isBadRequest());
        d = ok(call(qa, url + "/submit", Map.of("summary", "年度自查，发现 2 处问题已转 CAPA")));
        assertThat(d.get("check").get("status").asText()).isEqualTo("SUBMITTED");
        assertThat(d.get("check").get("conclusion").asText()).isEqualTo("DEFECT");
        call(qa, url + "/items/3", item("COMPLIANT", null)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不能登记结果")));
        assertThat(read(qso, "/api/dashboard").get("todos").toString()).contains("GMP 自查待签批").contains(no);

        // 退回后把关键项目改成不符合：结论变为严重缺陷
        call(qa, url + "/reject", Map.of("note", "x")).andExpect(status().isForbidden());
        ok(call(qso, url + "/reject", Map.of("note", "第 13 项再核对")));
        ok(call(qa, url + "/items/13", item("NONCOMPLIANT", "有一批受托厂出厂放行记录没审核就放行")));
        ok(call(qa, url + "/items/13/capa", capa(today.plusDays(15))));
        // 提交人和签批人不能是同一人
        d = ok(call(qso, url + "/submit", Map.of("summary", "关键项目不符合")));
        assertThat(d.get("check").get("conclusion").asText()).isEqualTo("SEVERE");
        call(qa, url + "/approve", Map.of("password", PWD)).andExpect(status().isForbidden());
        call(qso, url + "/approve", Map.of("password", PWD)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("签批人不能是提交人")));
        ok(call(qso, url + "/reject", Map.of("note", "换人提交")));
        ok(call(qa, url + "/submit", Map.of("summary", "关键项目不符合，已整改")));
        call(qso, url + "/approve", Map.of("password", "wrong")).andExpect(status().isBadRequest());
        d = ok(call(qso, url + "/approve", Map.of("password", PWD)));
        assertThat(d.get("check").get("status").asText()).isEqualTo("APPROVED");
        assertThat(d.get("check").get("approvedBy").asText()).isEqualTo("qso1");
        assertThat(d.get("check").get("conclusion").asText()).isEqualTo("SEVERE");
        assertThat(read(qso, "/api/dashboard").get("todos").toString()).doesNotContain("GMP 自查待签批");
        assertThat(read(qa, "/api/golive").toString()).contains("年度 GMP 自查").contains(no);

        // 签批后可以发起新的；作废要写原因，作废后也能再发起
        JsonNode next = ok(call(qa, BASE, period));
        String nextUrl = BASE + "/" + next.get("check").get("id").asLong();
        call(qa, nextUrl + "/cancel", Map.of("note", " ")).andExpect(status().isBadRequest());
        assertThat(ok(call(qa, nextUrl + "/cancel", Map.of("note", "期间选错了"))).get("check").get("status").asText()).isEqualTo("CANCELLED");
        call(qa, url + "/cancel", Map.of("note", "x")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不能作废")));
        ok(call(qa, BASE, period));
        assertThat(read(login("viewer1", PWD), BASE).size()).isEqualTo(3);
        assertThat(read(login("admin", "Admin@123456"), "/api/audit?q=" + no).toString()).contains("发起 GMP 自查").contains("GMP 自查转 CAPA").contains("签批 GMP 自查");
    }
}
