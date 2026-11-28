package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.rules.RetainSiteRule;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.qms.GmpEvidenceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 留样地点和报告（检查要点委托生产版序号 15，GMP 第五十六条）。会改默认地点、影响放行和总览，用单独的库。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-retain-site;MODE=MySQL;DB_CLOSE_DELAY=-1")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RetainSiteTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;
    private static final String BASE = "/api/qms/retain-sites";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TimeProvider time;
    @Autowired BatchRepository batches;
    @Autowired GmpEvidenceService evidence;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.cosmetic.scm.qms.RetainSampleRepository retains;

    private String login(String u) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", u, "password", u.equals("admin") ? "Admin@123456" : PWD)))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    private ResultActions call(String t, String url, Object body) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions update(String t, String url, Object body) throws Exception {
        return mvc.perform(put(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private JsonNode ok(ResultActions r) throws Exception {
        return json.readTree(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode read(String t, String url) throws Exception {
        return ok(mvc.perform(get(url).header("Authorization", "Bearer " + t)));
    }

    private JsonNode site(String t, String code) throws Exception {
        for (JsonNode n : read(t, BASE)) {
            if (code.equals(n.get("code").asText())) {
                return n;
            }
        }
        throw new AssertionError(code);
    }

    private JsonNode retainOf(String t, String batchNo) throws Exception {
        for (JsonNode n : read(t, "/api/qms/retains")) {
            if (batchNo.equals(n.get("batchNo").asText())) {
                return n;
            }
        }
        throw new AssertionError(batchNo);
    }

    private String todos(String t) throws Exception {
        return read(t, "/api/dashboard").get("todos").toString();
    }

    private Map<String, Object> siteReq(String code, String name, String address, String kind, boolean sameLocality, LocalDate usedSince, Boolean active, Boolean def) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("name", name);
        m.put("address", address);
        m.put("kind", kind);
        m.put("sameLocality", sameLocality);
        m.put("usedSince", usedSince == null ? null : usedSince.toString());
        m.put("active", active);
        m.put("defaultSite", def);
        return m;
    }

    private Map<String, Object> release(Long siteId) {
        Map<String, Object> m = new HashMap<>(Map.of("decision", "RELEASED", "reportNo", "R1", "note", "放行", "inspectionPassed", true, "recordsComplete", true,
                "consistentWithFiling", true, "retainQty", 2, "retainLocation", "B-01", "password", PWD));
        if (siteId != null) {
            m.put("retainSiteId", siteId);
        }
        return m;
    }

    private GmpEvidenceService.Evidence item15() {
        LocalDate today = time.today();
        return evidence.evidence(today.minusDays(30), today).stream().filter(e -> e.itemNo() == 15).findFirst().orElseThrow();
    }

    @Test
    @Order(1)
    void sitesDefaultReleaseReportDeadlineTodosAndSelfCheck() throws Exception {
        String admin = login("admin");
        String qa = login("qa1");
        String qso = login("qso1");
        String sales = login("sales1");
        LocalDate today = time.today();

        // 示例：住所的品质部留样室是默认地点，两批示例放行的留样都在这里；同城主仓留样柜 6 天前开始使用、还没报告
        JsonNode hq = site(qa, "RS-HQ");
        assertThat(hq.get("defaultSite").asBoolean()).isTrue();
        assertThat(hq.get("state").asText()).isEqualTo("NOT_REQUIRED");
        assertThat(hq.get("heldCount").asLong()).isEqualTo(2);
        assertThat(hq.get("fixed").asBoolean()).isTrue();
        JsonNode ec = site(qa, "RS-EC");
        assertThat(ec.get("state").asText()).isEqualTo("PENDING");
        assertThat(ec.get("firstRetainDate").asText()).isEqualTo(today.minusDays(6).toString());
        assertThat(ec.get("deadline").asText()).isEqualTo(RetainSiteRule.addWorkingDays(today.minusDays(6), 20).toString());
        assertThat(todos(qa)).contains("RETAIN_SITE_REPORT").contains("华东主仓留样柜");
        // 级别还受制度覆盖影响（示例里留样制度没有生效文件），这里只看留样地点的核对
        assertThat(item15().summary()).startsWith("期间内留样用到 2 个地点").contains("还没到报告截止日");
        long hqId = hq.get("id").asLong();
        long ecId = ec.get("id").asLong();

        // 只有质量部门能登记；不在所在地的其他经营场所不能登记；地址必填
        call(sales, BASE, siteReq("RS-X", "销售部", "某地", "DOMICILE", true, null, null, null)).andExpect(status().isForbidden());
        call(qa, BASE, siteReq("RS-X", "外地仓", "外省某市", "OTHER_PREMISES", false, null, null, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("所在地")));
        call(qa, BASE, siteReq("RS-X", "外地仓", "", "DOMICILE", true, null, null, null)).andExpect(status().isBadRequest());
        call(qa, BASE, siteReq("RS-HQ", "重复", "某地", "DOMICILE", true, null, null, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("已存在")));

        // 两个待上市放行的成品批次（期初导入）
        long fg = ok(call(admin, "/api/items", Map.of("code", "RS-FG", "name", "留样地点成品", "itemType", "FG", "unit", "瓶", "banned", false, "active", true,
                "shelfMonths", 36, "netG", 100, "filingNo", "备案RS"))).get("id").asLong();
        assertThat(fg).isPositive();
        JsonNode opening = ok(call(qso, "/api/opening-stock", Map.of("dryRun", false, "password", PWD, "rows", List.of(
                Map.of("itemCode", "RS-FG", "batchNo", "RS-B1", "status", "PENDING_RELEASE", "warehouseCode", "WH-MAIN", "qty", 10, "mfgDate", today.minusDays(5).toString()),
                Map.of("itemCode", "RS-FG", "batchNo", "RS-B2", "status", "PENDING_RELEASE", "warehouseCode", "WH-MAIN", "qty", 10, "mfgDate", today.minusDays(5).toString()),
                Map.of("itemCode", "RS-FG", "batchNo", "RS-B3", "status", "PENDING_RELEASE", "warehouseCode", "WH-MAIN", "qty", 10, "mfgDate", today.minusDays(5).toString())))));
        assertThat(opening.get("applied").asBoolean()).isTrue();
        long b1 = batches.findByBatchNo("RS-B1").orElseThrow().getId();
        long b2 = batches.findByBatchNo("RS-B2").orElseThrow().getId();
        long b3 = batches.findByBatchNo("RS-B3").orElseThrow().getId();

        // 不选地点：用默认地点
        ok(call(qso, "/api/quality/batches/" + b1 + "/market-release", release(null)));
        assertThat(retainOf(qa, "RS-B1").get("siteName").asText()).isEqualTo("品质部留样室");
        // 存放位置只填了柜号，留样室（贮存区域“品质部留样室”）超限时仍算在里面
        assertThat(retains.findHeldIn(com.cosmetic.scm.domain.RetainStatus.HELD, "品质部留样室", "RS-01"))
                .anyMatch(r -> r.getBatchId().equals(b1));

        // 改默认地点：原来的默认地点自动取消
        JsonNode ecDefault = ok(update(qa, BASE + "/" + ecId, siteReq("RS-EC", "华东主仓留样柜", ec.get("address").asText(), "OTHER_PREMISES", true,
                today.minusDays(6), true, true)));
        assertThat(ecDefault.get("defaultSite").asBoolean()).isTrue();
        assertThat(site(qa, "RS-HQ").get("defaultSite").asBoolean()).isFalse();
        ok(call(qso, "/api/quality/batches/" + b2 + "/market-release", release(null)));
        assertThat(retainOf(qa, "RS-B2").get("siteName").asText()).isEqualTo("华东主仓留样柜");

        // 有留样的地点不能改地址和类型，名称可以改
        update(qa, BASE + "/" + ecId, siteReq("RS-EC", "华东主仓留样柜", "搬到别处", "OTHER_PREMISES", true, today.minusDays(6), true, true))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("地址和类型不能改")));
        update(qa, BASE + "/" + ecId, siteReq("RS-EC", "华东主仓留样柜", ec.get("address").asText(), "MAIN_PREMISES", true, today.minusDays(6), true, true))
                .andExpect(status().isBadRequest());

        // 停用的地点不能选；停用的同时取消默认
        long tmp = ok(call(qa, BASE, siteReq("RS-TMP", "临时柜", "张江路 100 号 1 楼", "MAIN_PREMISES", true, null, null, false))).get("id").asLong();
        ok(update(qa, BASE + "/" + tmp, siteReq("RS-TMP", "临时柜", "张江路 100 号 1 楼", "MAIN_PREMISES", true, null, false, null)));
        call(qso, "/api/quality/batches/" + b3 + "/market-release", release(tmp)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("停用")));
        ok(call(qso, "/api/quality/batches/" + b3 + "/market-release", release(hqId)));
        assertThat(retainOf(qa, "RS-B3").get("siteName").asText()).isEqualTo("品质部留样室");

        // 报告：住所不需要报告；日期不能晚于今天；登记后不能重复，作废后可以重新登记
        call(qa, BASE + "/" + hqId + "/report", Map.of("reportDate", today.toString(), "authority", "浦东新区市场监督管理局")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("不需要报告")));
        call(qa, BASE + "/" + ecId + "/report", Map.of("reportDate", today.plusDays(1).toString(), "authority", "浦东新区市场监督管理局"))
                .andExpect(status().isBadRequest());
        call(sales, BASE + "/" + ecId + "/report", Map.of("reportDate", today.toString(), "authority", "浦东新区市场监督管理局")).andExpect(status().isForbidden());
        JsonNode reported = ok(call(qa, BASE + "/" + ecId + "/report", Map.of("reportDate", today.toString(), "authority", "浦东新区市场监督管理局", "ref", "HZ-001")));
        assertThat(reported.get("state").asText()).isEqualTo("REPORTED");
        call(qa, BASE + "/" + ecId + "/report", Map.of("reportDate", today.toString(), "authority", "又一次")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("先作废")));
        assertThat(todos(qa)).doesNotContain("RETAIN_SITE_REPORT");
        call(qa, BASE + "/" + ecId + "/void-report", Map.of("reason", " ")).andExpect(status().isBadRequest());
        assertThat(ok(call(qa, BASE + "/" + ecId + "/void-report", Map.of("reason", "回执编号录错"))).get("state").asText()).isEqualTo("PENDING");
        ok(call(qa, BASE + "/" + ecId + "/report", Map.of("reportDate", today.toString(), "authority", "浦东新区市场监督管理局", "ref", "HZ-002")));

        // 上线前就开始使用、已经过了截止日还没报告：逾期，总览标红，自查要注意
        JsonNode late = ok(call(qa, BASE, siteReq("RS-OLD", "嘉定分仓留样柜", "嘉定区某路 1 号", "OTHER_PREMISES", true, today.minusDays(60), null, false)));
        assertThat(late.get("state").asText()).isEqualTo("OVERDUE");
        assertThat(late.get("problem").asBoolean()).isTrue();
        assertThat(todos(qa)).contains("RETAIN_SITE_REPORT").contains("嘉定分仓留样柜").contains("已过");
        GmpEvidenceService.Evidence e = item15();
        assertThat(e.level()).isEqualTo("WARN");
        assertThat(e.summary()).startsWith("1 处要注意");
        assertThat(String.join("\n", e.details())).contains("嘉定分仓留样柜").contains("逾期未报告").contains("华东主仓留样柜");
        // 逾期后报告：仍标出来（逾期后报告）
        JsonNode lateReported = ok(call(qa, BASE + "/" + late.get("id").asLong() + "/report", Map.of("reportDate", today.toString(), "authority", "嘉定区市场监督管理局")));
        assertThat(lateReported.get("state").asText()).isEqualTo("REPORTED_LATE");
        assertThat(String.join("\n", item15().details())).contains("逾期后报告");

        // 没有地点的留样（V58 以前的）：总览提醒，质量部门指定后不能再改
        long sampleId = retainOf(qa, "RS-B1").get("id").asLong();
        jdbc.update("update retain_sample set site_id = null where id = ?", sampleId);
        assertThat(todos(qa)).contains("RETAIN_SITE_UNASSIGNED");
        assertThat(item15().details().get(0)).contains("没有指定留样地点");
        call(sales, BASE + "/" + hqId + "/assign", Map.of("sampleIds", List.of(sampleId))).andExpect(status().isForbidden());
        call(qa, BASE + "/" + tmp + "/assign", Map.of("sampleIds", List.of(sampleId))).andExpect(status().isBadRequest());
        ok(call(qa, BASE + "/" + hqId + "/assign", Map.of("sampleIds", List.of(sampleId))));
        assertThat(retainOf(qa, "RS-B1").get("siteName").asText()).isEqualTo("品质部留样室");
        call(qa, BASE + "/" + ecId + "/assign", Map.of("sampleIds", List.of(sampleId))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("不能再改")));
        assertThat(todos(qa)).doesNotContain("RETAIN_SITE_UNASSIGNED");

        // 审计和导出
        String audits = ok(mvc.perform(get("/api/audit").param("q", "留样地点").header("Authorization", "Bearer " + admin))).toString();
        assertThat(audits).contains("登记留样地点报告").contains("作废留样地点报告").contains("指定留样地点").contains("修改留样地点");
        mvc.perform(get("/api/reports/retain-sites").header("Authorization", "Bearer " + qa)).andExpect(status().isOk());
    }

    @Test
    @Order(2)
    void releaseWithoutDefaultNeedsAChoiceWhenSeveralSitesAreActive() throws Exception {
        String qa = login("qa1");
        // 本类另一个用例可能改过默认地点，这里先把所有在用地点的默认取消
        for (JsonNode n : read(qa, BASE)) {
            if (n.get("defaultSite").asBoolean()) {
                ok(update(qa, BASE + "/" + n.get("id").asLong(), siteReq(n.get("code").asText(), n.get("name").asText(), n.get("address").asText(),
                        n.get("kind").asText(), n.get("sameLocality").asBoolean(), n.get("usedSince").isNull() ? null : LocalDate.parse(n.get("usedSince").asText()),
                        true, false)));
            }
        }
        String admin = login("admin");
        String qso = login("qso1");
        LocalDate today = time.today();
        ok(call(admin, "/api/items", Map.of("code", "RS-FG2", "name", "留样地点成品二", "itemType", "FG", "unit", "瓶", "banned", false, "active", true,
                "shelfMonths", 36, "netG", 100, "filingNo", "备案RS2")));
        ok(call(qso, "/api/opening-stock", Map.of("dryRun", false, "password", PWD, "rows", List.of(
                Map.of("itemCode", "RS-FG2", "batchNo", "RS-C1", "status", "PENDING_RELEASE", "warehouseCode", "WH-MAIN", "qty", 10, "mfgDate", today.minusDays(5).toString())))));
        long b = batches.findByBatchNo("RS-C1").orElseThrow().getId();
        call(qso, "/api/quality/batches/" + b + "/market-release", release(null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("请选择这批留样存放在哪个地点")));
        ok(call(qso, "/api/quality/batches/" + b + "/market-release", release(site(qa, "RS-HQ").get("id").asLong())));
    }
}
