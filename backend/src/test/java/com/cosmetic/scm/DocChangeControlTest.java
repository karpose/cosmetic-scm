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

    private Map<String, Object> assess(LocalDate on, String method, Object... lines) {
        List<Map<String, Object>> rs = new java.util.ArrayList<>();
        for (Object l : lines) {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) l;
            rs.add(m);
        }
        return Map.of("assessedOn", on.toString(), "method", method, "results", rs);
    }

    private static Map<String, Object> line(String user, boolean passed, Integer score, String note) {
        Map<String, Object> m = new HashMap<>(Map.of("username", user, "passed", passed));
        if (score != null) {
            m.put("score", score);
        }
        if (note != null) {
            m.put("note", note);
        }
        return m;
    }

    private JsonNode person(JsonNode d, String user) {
        for (JsonNode p : d.get("ack").get("people")) {
            if (p.get("username").asText().equals(user)) {
                return p;
            }
        }
        throw new AssertionError(user);
    }

    @Test
    void trainingAssessmentsFollowTheReadAcknowledgementAndAreRecordedByAnotherQualityPerson() throws Exception {
        String qa = login("qa1");
        String qso = login("qso1");
        String prod = login("prod1");
        LocalDate today = time.today();
        // 没设岗位不能要求考核
        JsonNode bare = ok(call(qa, "/api/docs", doc("SOP-AS-0", null)));
        call(qa, "/api/docs/" + bare.get("doc").get("id").asLong() + "/train-roles", Map.of("trainRoles", List.of(), "assessRequired", true))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("先设需要确认的岗位")));

        Map<String, Object> m = doc("SOP-AS-1", null);
        m.put("trainRoles", List.of("PRODUCTION", "QA"));
        JsonNode d = ok(call(qa, "/api/docs", m));
        String base = "/api/docs/" + d.get("doc").get("id").asLong();
        long rev1 = d.get("revisions").get(0).get("id").asLong();
        upload(qa, rev1);
        ok(call(qso, base + "/revisions/" + rev1 + "/approve", Map.of("password", PWD)));
        String url = base + "/revisions/" + rev1 + "/assessments";
        // 没要求考核时不能登记
        call(qa, url, assess(today, "WRITTEN", line("prod1", true, null, null))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("没有要求考核")));
        d = ok(call(qa, base + "/train-roles", Map.of("trainRoles", List.of("PRODUCTION", "QA"), "assessRequired", true)));
        assertThat(d.get("ack").get("assessRequired").asBoolean()).isTrue();
        assertThat(d.get("doc").get("assessRequired").asBoolean()).isTrue();

        // 要先确认已读
        call(qa, url, assess(today, "WRITTEN", line("prod1", true, null, null))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("先阅读再考核")));
        ok(call(prod, base + "/revisions/" + rev1 + "/ack", Map.of()));
        d = ok(call(qa, base + "/revisions/" + rev1 + "/ack", Map.of()));
        assertThat(d.get("doc").get("assessPending").asInt()).isEqualTo(2);
        assertThat(read(qa, "/api/dashboard").get("todos").toString()).contains("受控文件待考核").contains("SOP-AS-1");

        // 各种不能登记的情况
        call(prod, url, assess(today, "WRITTEN", line("qa1", true, null, null))).andExpect(status().isForbidden());
        call(qa, url, assess(today, "WRITTEN", line("qa1", true, null, null))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不能给自己")));
        call(qa, url, assess(today, "WRITTEN", line("sales1", true, null, null))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不在需要确认")));
        call(qa, url, assess(today.plusDays(1), "WRITTEN", line("prod1", true, null, null))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不能晚于今天")));
        call(qa, url, assess(today.minusDays(1), "WRITTEN", line("prod1", true, null, null))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("签批日期")));
        call(qa, url, assess(today, "WRITTEN", line("prod1", true, null, null), line("prod1", false, null, "x"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("填了两次")));
        call(qa, url, assess(today, "WRITTEN", line("prod1", false, 55, null))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("写明原因")));
        call(qa, url, assess(today, "WRITTEN", line("prod1", true, 101, null))).andExpect(status().isBadRequest());

        // 一次登记两人：一个没通过、一个还没确认的不能夹带（整批不成功）
        call(qa, url, assess(today, "WRITTEN", line("prod1", false, 55, "称量步骤答错"), line("qso1", true, null, null))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("先阅读再考核")));
        assertThat(read(qa, base).get("ack").get("people").toString()).doesNotContain("称量步骤答错");
        d = ok(call(qso, url, assess(today, "WRITTEN", line("prod1", false, 55, "称量步骤答错，下周补考"), line("qa1", true, 92, null))));
        assertThat(person(d, "prod1").get("passed").asBoolean()).isFalse();
        assertThat(person(d, "prod1").get("assessMethodLabel").asText()).isEqualTo("笔试");
        assertThat(person(d, "qa1").get("score").asInt()).isEqualTo(92);
        assertThat(d.get("ack").get("passed").asInt()).isEqualTo(1);
        assertThat(d.get("doc").get("assessPending").asInt()).isEqualTo(1);
        call(qso, url, assess(today, "ORAL", line("qa1", true, null, null))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("已考核通过")));
        // 补考通过
        d = ok(call(qa, url, assess(today, "PRACTICAL", line("prod1", true, null, "现场操作复核"))));
        assertThat(person(d, "prod1").get("passed").asBoolean()).isTrue();
        assertThat(d.get("doc").get("assessPending").asInt()).isZero();
        // qso1 还没确认已读，待确认的提醒还在；待考核的提醒没了
        assertThat(read(qa, "/api/dashboard").get("todos").toString()).doesNotContain("受控文件待考核").contains("受控文件待确认");

        // 出新版：要重新确认、重新考核；生效超过 30 天没考完算超期
        d = ok(call(qa, base + "/revisions", Map.of("changeSummary", "改称量步骤")));
        long rev2 = d.get("revisions").get(0).get("id").asLong();
        upload(qa, rev2);
        d = ok(call(qso, base + "/revisions/" + rev2 + "/approve", Map.of("password", PWD)));
        assertThat(d.get("ack").get("passed").asInt()).isZero();
        call(qa, url, assess(today, "WRITTEN", line("prod1", true, null, null))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不是现行")));
        ok(call(prod, base + "/revisions/" + rev2 + "/ack", Map.of()));
        var r2 = revisionRepo.findById(rev2).orElseThrow();
        r2.setEffectiveDate(today.minusDays(31));
        revisionRepo.save(r2);
        d = read(qa, base);
        assertThat(d.get("doc").get("assessOverdue").asBoolean()).isTrue();
        assertThat(read(qa, "/api/dashboard").get("todos").toString()).contains("受控文件考核已超期");
        assertThat(read(qa, "/api/docs/compliance-board").toString()).contains("ASSESS_PENDING");

        // 导出培训考核记录：每次考核一行，没通过的原因、补考都在
        String sheet = sheetOf(mvc.perform(get("/api/reports/training-records").header("Authorization", "Bearer " + login("viewer1"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(sheet).contains("SOP-AS-1").contains("称量步骤答错，下周补考").contains("未通过").contains("现场操作复核").contains("92").contains("待考核");
        assertThat(read(qa, "/api/audit?q=SOP-AS-1").toString()).contains("登记受控文件考核").contains("未通过");
    }

    private static String sheetOf(byte[] xlsx) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (var z = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(xlsx), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.getName().startsWith("xl/")) {
                    sb.append(new String(z.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return sb.toString();
    }

    private JsonNode coverage(String t, String code) throws Exception {
        for (JsonNode r : read(t, "/api/docs/procedures")) {
            if (r.get("code").asText().equals(code)) {
                return r;
            }
        }
        throw new AssertionError(code);
    }

    @Test
    void documentsAreTaggedWithTheProceduresTheChecklistRequires() throws Exception {
        String qa = login("qa1");
        String qso = login("qso1");
        Map<String, Object> m = doc("SOP-PR-1", null);
        m.put("procedures", List.of("BOGUS"));
        call(qa, "/api/docs", m).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不认识的制度")));
        m.put("procedures", List.of("RELEASE", "RETAIN"));
        JsonNode d = ok(call(qa, "/api/docs", m));
        String base = "/api/docs/" + d.get("doc").get("id").asLong();
        assertThat(d.get("doc").get("procedures").toString()).contains("RELEASE").contains("RETAIN");
        // 还没生效：在建
        assertThat(coverage(qa, "RELEASE").get("state").asText()).isEqualTo("DRAFTING");
        assertThat(coverage(qa, "SALES_RECORD").get("state").asText()).isEqualTo("MISSING");
        assertThat(coverage(qa, "MATERIAL_RELEASE").get("optional").asBoolean()).isTrue();
        long rev = d.get("revisions").get(0).get("id").asLong();
        upload(qa, rev);
        ok(call(qso, base + "/revisions/" + rev + "/approve", Map.of("password", PWD)));
        JsonNode release = coverage(qa, "RELEASE");
        assertThat(release.get("state").asText()).isEqualTo("ESTABLISHED");
        assertThat(release.get("docs").get(0).get("docCode").asText()).isEqualTo("SOP-PR-1");
        assertThat(release.get("items").toString()).contains("13");
        // 改对应的制度：只给主数据和质量
        call(login("sales1"), base + "/procedures", Map.of("procedures", List.of("RELEASE"))).andExpect(status().isForbidden());
        d = ok(call(qa, base + "/procedures", Map.of("procedures", List.of("RELEASE", "SALES_RECORD"))));
        assertThat(d.get("doc").get("procedures").toString()).contains("SALES_RECORD").doesNotContain("RETAIN");
        assertThat(coverage(qa, "RETAIN").get("state").asText()).isEqualTo("MISSING");
        assertThat(coverage(qa, "SALES_RECORD").get("state").asText()).isEqualTo("ESTABLISHED");
        assertThat(read(login("admin", "Admin@123456"), "/api/golive").toString()).contains("必备制度");
        assertThat(read(qa, "/api/audit?q=SOP-PR-1").toString()).contains("修改文件对应的制度").contains("产品放行、留样 改为");
        // 作废后不算
        ok(call(qso, base + "/obsolete", Map.of("note", "合并")));
        assertThat(coverage(qa, "RELEASE").get("state").asText()).isEqualTo("MISSING");
        call(qa, base + "/procedures", Map.of("procedures", List.of())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("作废")));
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
