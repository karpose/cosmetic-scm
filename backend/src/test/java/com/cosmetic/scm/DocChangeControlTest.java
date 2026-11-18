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

/** 受控文件和变更控制。用单独的库。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-docchange;MODE=MySQL;DB_CLOSE_DELAY=-1")
class DocChangeControlTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TimeProvider time;
    @Autowired com.cosmetic.scm.plm.DocRevisionRepository revisionRepo;

    private String login(String u) throws Exception {
        return login(u, PWD);
    }

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

    private void upload(String t, long revId) throws Exception {
        mvc.perform(multipart("/api/attachments").file(new MockMultipartFile("file", "SOP.pdf", "application/pdf", "%PDF-1.4 test".getBytes(StandardCharsets.UTF_8)))
                .param("ownerType", "DOC_REVISION").param("ownerId", String.valueOf(revId)).header("Authorization", "Bearer " + t)).andExpect(status().isOk());
    }

    private Map<String, Object> doc(String code, LocalDate effective) {
        Map<String, Object> m = new HashMap<>(Map.of("docCode", code, "title", "测试文件", "category", "SOP", "ownerDept", "质量部", "reviewMonths", 24, "changeSummary", "首版"));
        if (effective != null) {
            m.put("effectiveDate", effective.toString());
        }
        return m;
    }

    @Test
    void revisionsNeedAFileAndAnotherQsoAndTheCurrentOneFollowsTheEffectiveDate() throws Exception {
        String qa = login("qa1");
        String qso = login("qso1");
        LocalDate today = time.today();

        call(login("purchase1"), "/api/docs", doc("SOP-T-1", null)).andExpect(status().isForbidden());
        call(qa, "/api/docs", doc("SOP-T-1", today.minusDays(1))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("生效日期")));
        call(qa, "/api/docs", doc("SOP T 1", null)).andExpect(status().isBadRequest());
        JsonNode d = ok(call(qa, "/api/docs", doc("sop-t-1", null)));
        long id = d.get("doc").get("id").asLong();
        assertThat(d.get("doc").get("docCode").asText()).isEqualTo("SOP-T-1");
        call(qa, "/api/docs", doc("SOP-T-1", null)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("已存在")));
        long rev1 = d.get("revisions").get(0).get("id").asLong();
        String base = "/api/docs/" + id;
        assertThat(read(qso, "/api/dashboard").get("todos").toString()).contains("受控文件待签批").contains("SOP-T-1");

        // 只有 QSO，要有文件，不能是起草人
        call(qa, base + "/revisions/" + rev1 + "/approve", Map.of("password", PWD)).andExpect(status().isForbidden());
        call(qso, base + "/revisions/" + rev1 + "/approve", Map.of("password", PWD)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("没有上传文件")));
        upload(qa, rev1);
        d = ok(call(qso, base + "/revisions/" + rev1 + "/approve", Map.of("password", PWD)));
        assertThat(d.get("doc").get("currentRev").asInt()).isEqualTo(1);
        assertThat(d.get("doc").get("reviewDue").asText()).isEqualTo(today.plusMonths(24).toString());
        JsonNode own = ok(call(qso, "/api/docs", doc("SOP-T-2", null)));
        upload(qso, own.get("revisions").get(0).get("id").asLong());
        call(qso, "/api/docs/" + own.get("doc").get("id").asLong() + "/revisions/" + own.get("revisions").get(0).get("id").asLong() + "/approve", Map.of("password", PWD))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("起草人")));

        // 第 2 版 10 天后生效：签批后现行仍是第 1 版
        d = ok(call(qa, base + "/revisions", Map.of("changeSummary", "增加复核步骤", "effectiveDate", today.plusDays(10).toString())));
        long rev2 = d.get("revisions").get(0).get("id").asLong();
        call(qa, base + "/revisions", Map.of("changeSummary", "又一版")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("待签批")));
        upload(qa, rev2);
        d = ok(call(qso, base + "/revisions/" + rev2 + "/approve", Map.of("password", PWD)));
        assertThat(d.get("doc").get("currentRev").asInt()).isEqualTo(1);
        assertThat(d.get("doc").get("upcomingRev").asInt()).isEqualTo(2);
        assertThat(d.get("doc").get("upcomingEffective").asText()).isEqualTo(today.plusDays(10).toString());

        // 退回、复审、作废
        d = ok(call(qa, base + "/revisions", Map.of("changeSummary", "第三版")));
        long rev3 = d.get("revisions").get(0).get("id").asLong();
        ok(call(qso, base + "/revisions/" + rev3 + "/reject", Map.of("note", "写得不清楚")));
        call(qa, base + "/review", Map.of("note", "x")).andExpect(status().isForbidden());
        ok(call(qso, base + "/review", Map.of("note", "仍然适用")));
        ok(call(qa, base + "/revisions", Map.of("changeSummary", "第四版")));
        d = ok(call(qso, base + "/obsolete", Map.of("note", "流程合并")));
        assertThat(d.get("doc").get("status").asText()).isEqualTo("OBSOLETE");
        assertThat(d.get("doc").get("pendingRevisionId").isNull()).isTrue();
        call(qa, base + "/revisions", Map.of("changeSummary", "x")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("作废")));
    }

    private JsonNode copy(JsonNode d, int revNo, int copyNo) {
        for (JsonNode c : d.get("copies")) {
            if (c.get("revNo").asInt() == revNo && c.get("copyNo").asInt() == copyNo) {
                return c;
            }
        }
        throw new AssertionError(revNo + "/" + copyNo);
    }

    @Test
    void paperCopiesAreIssuedForTheCurrentRevisionAndOldOnesMustComeBack() throws Exception {
        String qa = login("qa1");
        String qso = login("qso1");
        LocalDate today = time.today();
        // 没有生效版本的不能发
        JsonNode draft = ok(call(qa, "/api/docs", doc("SOP-CP-0", null)));
        call(qa, "/api/docs/" + draft.get("doc").get("id").asLong() + "/copies", Map.of("holder", "车间", "quantity", 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("还没有生效的版本")));

        JsonNode d = ok(call(qa, "/api/docs", doc("SOP-CP-1", null)));
        String base = "/api/docs/" + d.get("doc").get("id").asLong();
        long rev1 = d.get("revisions").get(0).get("id").asLong();
        upload(qa, rev1);
        ok(call(qso, base + "/revisions/" + rev1 + "/approve", Map.of("password", PWD)));
        call(login("prod1"), base + "/copies", Map.of("holder", "车间", "quantity", 1)).andExpect(status().isForbidden());
        call(qa, base + "/copies", Map.of("holder", "车间", "quantity", 0)).andExpect(status().isBadRequest());
        call(qa, base + "/copies", Map.of("holder", "车间", "quantity", 21)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("最多发 20 份")));
        call(qa, base + "/copies", Map.of("holder", " ", "quantity", 1)).andExpect(status().isBadRequest());

        // 按版本连续编号
        ok(call(qa, base + "/copies", Map.of("holder", "灌装车间", "quantity", 2)));
        d = ok(call(qa, base + "/copies", Map.of("holder", "QA 办公室", "quantity", 1)));
        assertThat(d.get("doc").get("copiesOut").asInt()).isEqualTo(3);
        assertThat(d.get("doc").get("copiesToRetrieve").asInt()).isZero();
        assertThat(copy(d, 1, 3).get("holder").asText()).isEqualTo("QA 办公室");
        assertThat(copy(d, 1, 1).get("mustRetrieve").asBoolean()).isFalse();
        // 现行版本也可以提前收回
        String c3 = base + "/copies/" + copy(d, 1, 3).get("id").asLong();
        d = ok(call(qa, c3 + "/retrieve", Map.of("note", "办公室不再需要")));
        assertThat(copy(d, 1, 3).get("status").asText()).isEqualTo("RETRIEVED");
        assertThat(copy(d, 1, 3).get("closedBy").asText()).isEqualTo("qa1");
        call(qa, c3 + "/retrieve", Map.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("已回收")));

        // 第 2 版生效：第 1 版在用的两份要收回，7 天期限
        d = ok(call(qa, base + "/revisions", Map.of("changeSummary", "改步骤")));
        long rev2 = d.get("revisions").get(0).get("id").asLong();
        upload(qa, rev2);
        d = ok(call(qso, base + "/revisions/" + rev2 + "/approve", Map.of("password", PWD)));
        assertThat(d.get("doc").get("copiesToRetrieve").asInt()).isEqualTo(2);
        assertThat(d.get("doc").get("retrieveDue").asText()).isEqualTo(today.plusDays(7).toString());
        assertThat(d.get("doc").get("retrieveOverdue").asBoolean()).isFalse();
        assertThat(copy(d, 1, 1).get("mustRetrieve").asBoolean()).isTrue();
        assertThat(read(qa, "/api/dashboard").get("todos").toString()).contains("纸质受控文件待回收").contains("SOP-CP-1");
        // 新版从第 1 份重新编号
        d = ok(call(qa, base + "/copies", Map.of("holder", "灌装车间", "quantity", 1)));
        assertThat(copy(d, 2, 1).get("mustRetrieve").asBoolean()).isFalse();

        // 过了期限算超期
        var r2 = revisionRepo.findById(rev2).orElseThrow();
        r2.setEffectiveDate(today.minusDays(10));
        revisionRepo.save(r2);
        d = read(qa, base);
        assertThat(d.get("doc").get("retrieveOverdue").asBoolean()).isTrue();
        assertThat(copy(d, 1, 2).get("overdue").asBoolean()).isTrue();
        assertThat(read(qa, "/api/dashboard").get("todos").toString()).contains("纸质受控文件回收已超期");

        // 遗失要写原因
        String c2 = base + "/copies/" + copy(d, 1, 2).get("id").asLong();
        call(qa, c2 + "/lost", Map.of("note", " ")).andExpect(status().isBadRequest());
        d = ok(call(qa, c2 + "/lost", Map.of("note", "车间搬迁时丢失")));
        assertThat(copy(d, 1, 2).get("status").asText()).isEqualTo("LOST");
        d = ok(call(qa, base + "/copies/" + copy(d, 1, 1).get("id").asLong() + "/retrieve", Map.of()));
        assertThat(d.get("doc").get("copiesToRetrieve").asInt()).isZero();
        assertThat(d.get("doc").get("copiesOut").asInt()).isEqualTo(1);
        // 别的文件的副本不能拿来处理
        call(qa, "/api/docs/" + draft.get("doc").get("id").asLong() + "/copies/" + copy(d, 2, 1).get("id").asLong() + "/retrieve", Map.of()).andExpect(status().isNotFound());

        // 作废后在用的都要收回，作废的文件不能再发，但能收回
        d = ok(call(qso, base + "/obsolete", Map.of("note", "流程合并")));
        assertThat(d.get("doc").get("copiesToRetrieve").asInt()).isEqualTo(1);
        assertThat(d.get("doc").get("retrieveDue").asText()).isEqualTo(today.plusDays(7).toString());
        call(qa, base + "/copies", Map.of("holder", "车间", "quantity", 1)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("作废")));
        d = ok(call(qa, base + "/copies/" + copy(d, 2, 1).get("id").asLong() + "/retrieve", Map.of("note", "已收回销毁")));
        assertThat(d.get("doc").get("copiesOut").asInt()).isZero();
        assertThat(read(qa, "/api/audit?q=SOP-CP-1").toString()).contains("发放受控文件纸质副本").contains("第 1 版第 1～2 份，持有人 灌装车间")
                .contains("受控文件纸质副本遗失").contains("车间搬迁时丢失").contains("收回受控文件纸质副本");
    }

    @Test
    void peopleInTheChosenRolesAcknowledgeEachNewRevisionOnce() throws Exception {
        String qa = login("qa1");
        String qso = login("qso1");
        String prod = login("prod1");
        String sales = login("sales1");
        Map<String, Object> m = doc("SOP-ACK-1", null);
        m.put("trainRoles", List.of("PRODUCTION", "SALES"));
        JsonNode d = ok(call(qa, "/api/docs", m));
        long id = d.get("doc").get("id").asLong();
        String base = "/api/docs/" + id;
        long rev1 = d.get("revisions").get(0).get("id").asLong();
        upload(qa, rev1);
        d = ok(call(qso, base + "/revisions/" + rev1 + "/approve", Map.of("password", PWD)));
        assertThat(d.get("ack").get("total").asInt()).isEqualTo(2);
        assertThat(d.get("ack").get("done").asInt()).isZero();
        assertThat(d.get("doc").get("ackPending").asInt()).isEqualTo(2);
        assertThat(read(prod, "/api/docs/my-pending").toString()).contains("SOP-ACK-1");
        assertThat(read(qa, "/api/docs/my-pending").toString()).doesNotContain("SOP-ACK-1");
        assertThat(read(qa, "/api/dashboard").get("todos").toString()).contains("受控文件待确认").contains("2 人");

        // 只有应确认的人能确认，每人每版一次
        call(login("viewer1"), base + "/revisions/" + rev1 + "/ack", Map.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不需要确认")));
        ok(call(prod, base + "/revisions/" + rev1 + "/ack", Map.of()));
        call(prod, base + "/revisions/" + rev1 + "/ack", Map.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("已经确认")));
        assertThat(read(prod, "/api/docs/my-pending").toString()).doesNotContain("SOP-ACK-1");
        d = ok(call(sales, base + "/revisions/" + rev1 + "/ack", Map.of()));
        assertThat(d.get("ack").get("done").asInt()).isEqualTo(2);
        assertThat(read(qa, "/api/dashboard").get("todos").toString()).doesNotContain("SOP-ACK-1");

        // 出新版：要重新确认，旧版不能再确认
        long rev2 = ok(call(qa, base + "/revisions", Map.of("changeSummary", "第二版"))).get("revisions").get(0).get("id").asLong();
        upload(qa, rev2);
        d = ok(call(qso, base + "/revisions/" + rev2 + "/approve", Map.of("password", PWD)));
        assertThat(d.get("ack").get("revNo").asInt()).isEqualTo(2);
        assertThat(d.get("ack").get("done").asInt()).isZero();
        call(prod, base + "/revisions/" + rev1 + "/ack", Map.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不用确认")));

        // 改岗位：只给质量，门户岗位不行
        call(login("purchase1"), base + "/train-roles", Map.of("trainRoles", List.of("QA"))).andExpect(status().isForbidden());
        call(qa, base + "/train-roles", Map.of("trainRoles", List.of("OEM_PORTAL"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("门户")));
        d = ok(call(qa, base + "/train-roles", Map.of("trainRoles", List.of())));
        assertThat(d.get("ack").isNull()).isTrue();
        assertThat(d.get("doc").get("ackPending").asInt()).isZero();
        assertThat(read(qa, "/api/audit?q=修改文件确认岗位").toString()).contains("不需要确认");
    }

    @Test
    void changeGoesFromRequestToAssessmentApprovalActionsAndClosure() throws Exception {
        String qa = login("qa1");
        String qso = login("qso1");
        String sales = login("sales1");
        LocalDate today = time.today();
        Map<String, Object> req = Map.of("title", "标签改版", "changeType", "LABEL", "reason", "新规要求", "description", "增加使用方法", "scope", "面霜");
        call(login("viewer1"), "/api/changes", req).andExpect(status().isForbidden());
        JsonNode c = ok(call(sales, "/api/changes", req));
        long id = c.get("change").get("id").asLong();
        String base = "/api/changes/" + id;
        assertThat(read(qa, "/api/dashboard").get("todos").toString()).contains("变更待评估");

        // 评估：勾选的生成行动；负责人账号要存在
        Map<String, Object> bad = Map.of("risk", "MEDIUM", "impact", "x", "needFiling", false, "needStability", false, "needLabel", false, "needValidation", false,
                "actions", List.of(Map.of("content", "x", "owner", "nobody", "dueDate", today.plusDays(5).toString())));
        call(qa, base + "/assess", bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不存在")));
        call(login("purchase1"), base + "/assess", bad).andExpect(status().isForbidden());
        c = ok(call(qa, base + "/assess", Map.of("risk", "MEDIUM", "impact", "影响标签和备案", "needFiling", true, "needStability", false, "needLabel", true, "needValidation", false,
                "actions", List.of(Map.of("content", "通知经销商", "owner", "sales1", "dueDate", today.plusDays(5).toString())))));
        assertThat(c.get("actions").size()).isEqualTo(3);
        assertThat(c.get("actions").get(0).get("owner").asText()).isEqualTo("qa1");
        assertThat(c.get("change").get("status").asText()).isEqualTo("ASSESSED");
        assertThat(read(qso, "/api/dashboard").get("todos").toString()).contains("变更待批准");

        // 批准前不能做行动；批准要密码
        long a1 = c.get("actions").get(0).get("id").asLong();
        long a3 = c.get("actions").get(2).get("id").asLong();
        call(qa, base + "/actions/" + a1 + "/done", Map.of("note", "x")).andExpect(status().isBadRequest());
        call(qa, base + "/approve", Map.of("password", PWD)).andExpect(status().isForbidden());
        call(qso, base + "/approve", Map.of("password", "wrong")).andExpect(status().isBadRequest());
        ok(call(qso, base + "/approve", Map.of("password", PWD)));

        // 行动：负责人本人或质量人员
        call(login("prod1"), base + "/actions/" + a1 + "/done", Map.of("note", "x")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("负责人")));
        ok(call(sales, base + "/actions/" + a3 + "/done", Map.of("note", "已通知")));
        call(sales, base + "/actions/" + a3 + "/done", Map.of("note", "x")).andExpect(status().isBadRequest());
        call(qso, base + "/close", Map.of("note", "x", "password", PWD)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("没完成")));
        c = ok(call(qa, base + "/actions", Map.of("content", "旧标签销毁", "owner", "qa1", "dueDate", today.toString())));
        for (JsonNode a : c.get("actions")) {
            if (!a.get("done").asBoolean()) {
                ok(call(qa, base + "/actions/" + a.get("id").asLong() + "/done", Map.of("note", "完成")));
            }
        }
        assertThat(read(qso, "/api/dashboard").get("todos").toString()).contains("变更待关闭");
        c = ok(call(qso, base + "/close", Map.of("note", "标签已全部更换", "password", PWD)));
        assertThat(c.get("change").get("status").asText()).isEqualTo("CLOSED");
        assertThat(c.get("change").get("closedBy").asText()).isEqualTo("qso1");
    }

    @Test
    void requesterCannotApproveOrCloseTheirOwnChangeAndOnlyTheyOrQualityCanCancel() throws Exception {
        String qso = login("qso1");
        String qa = login("qa1");
        Map<String, Object> req = Map.of("title", "换设备", "changeType", "EQUIPMENT", "reason", "老化", "description", "更换乳化锅");
        long id = ok(call(qso, "/api/changes", req)).get("change").get("id").asLong();
        ok(call(qa, "/api/changes/" + id + "/assess", Map.of("risk", "HIGH", "impact", "要验证", "needFiling", false, "needStability", false, "needLabel", false, "needValidation", true)));
        call(qso, "/api/changes/" + id + "/approve", Map.of("password", PWD)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("申请人")));

        long other = ok(call(login("sales1"), "/api/changes", req)).get("change").get("id").asLong();
        call(login("prod1"), "/api/changes/" + other + "/cancel", Map.of("note", "x")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("申请人")));
        ok(call(login("sales1"), "/api/changes/" + other + "/cancel", Map.of("note", "不改了")));
        ok(call(qso, "/api/changes/" + id + "/reject", Map.of("note", "预算不够")));
        call(qso, "/api/changes/" + id + "/approve", Map.of("password", PWD)).andExpect(status().isBadRequest());
    }
}
