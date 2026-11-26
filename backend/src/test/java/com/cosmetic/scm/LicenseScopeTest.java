package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.cosmetic.scm.audit.AuditRepository;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.oemcontract.OemContractService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 受托厂许可项目标注（检查要点委托生产版第 2 项）：眼部护肤类、儿童护肤类成品只能交给许可项目标注了相应生产条件的工厂。
 * 会改示例成品和受托厂的主数据、签批示例合同，用单独的库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-license-scope;MODE=MySQL;DB_CLOSE_DELAY=-1")
class LicenseScopeTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TimeProvider time;
    @Autowired AuditRepository audits;

    private String login(String u, String p) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", u, "password", p)))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    private ResultActions call(String t, String url, Object body) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions putJson(String t, String url, Object body) throws Exception {
        return mvc.perform(put(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private JsonNode ok(ResultActions r) throws Exception {
        return json.readTree(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode read(String t, String url) throws Exception {
        return ok(mvc.perform(get(url).header("Authorization", "Bearer " + t)));
    }

    private ObjectNode byCode(String t, String url, String code) throws Exception {
        for (JsonNode n : read(t, url)) {
            if (code.equals(n.get("code").asText())) {
                return (ObjectNode) n;
            }
        }
        throw new AssertionError(code);
    }

    private String todos(String t) throws Exception {
        return read(t, "/api/dashboard").get("todos").toString();
    }

    private Map<Integer, JsonNode> evidence(String t, long checkId) throws Exception {
        Map<Integer, JsonNode> byNo = new HashMap<>();
        read(t, "/api/qms/self-checks/" + checkId + "/evidence").forEach(e -> byNo.put(e.get("itemNo").asInt(), e));
        return byNo;
    }

    private String lastAudit(String action) {
        return audits.findAllByOrderByIdDesc(PageRequest.of(0, 50)).stream().filter(a -> action.equals(a.getAction()))
                .findFirst().map(a -> a.getDetail() == null ? "" : a.getDetail()).orElse("");
    }

    @Test
    void eyeAndChildCareProductsNeedMarkedFactoryLicense() throws Exception {
        String admin = login("admin", "Admin@123456");
        String prod = login("prod1", PWD);
        String qso = login("qso1", PWD);
        String buyer = login("purchase1", PWD);
        String qa = login("qa1", PWD);
        LocalDate today = time.today();
        ObjectNode serum = byCode(admin, "/api/items", "FG-SER-001");
        ObjectNode a3 = byCode(admin, "/api/partners", "OEM-A3");
        long serumId = serum.get("id").asLong();
        long a3Id = a3.get("id").asLong();
        long own = byCode(admin, "/api/partners", "OWN-01").get("id").asLong();

        // 默认都没标：普通成品不受影响
        assertThat(serum.get("eyeCare").asBoolean()).isFalse();
        assertThat(a3.get("licenseEyeCare").asBoolean()).isFalse();
        assertThat(todos(qso)).doesNotContain("受托厂许可项目没有标注生产条件");
        assertThat(read(prod, "/api/oem-contracts/check?factoryId=" + a3Id + "&fgItemId=" + serumId).get("licenseMissing").size()).isZero();

        // 精华液标为眼部护肤类：主数据修改记录前后值
        serum.put("eyeCare", true);
        JsonNode saved = ok(putJson(admin, "/api/items/" + serumId, serum));
        assertThat(saved.get("eyeCare").asBoolean()).isTrue();
        assertThat(lastAudit("修改物料")).contains("眼部护肤类 否 → 是");
        // 旧客户端不带这两个字段：保留原值
        serum.remove("eyeCare");
        serum.remove("childCare");
        assertThat(ok(putJson(admin, "/api/items/" + serumId, serum)).get("eyeCare").asBoolean()).isTrue();

        // 下单前核对、下委托单、自有工厂也一样
        JsonNode chk = read(prod, "/api/oem-contracts/check?factoryId=" + a3Id + "&fgItemId=" + serumId);
        assertThat(chk.get("licenseMissing").toString()).contains("眼部护肤类");
        call(prod, "/api/mfg-orders", Map.of("factoryId", a3Id, "fgItemId", serumId, "plannedQty", 100)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("禾川生物（示例） 的生产许可项目没有标注具备眼部护肤类化妆品生产条件，不能委托生产")));
        call(prod, "/api/mfg-orders", Map.of("factoryId", own, "fgItemId", serumId, "plannedQty", 100)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("不能生产")));
        assertThat(read(prod, "/api/oem-contracts/check?factoryId=" + own + "&fgItemId=" + serumId).get("licenseMissing").toString()).contains("眼部护肤类");

        // 示例里还没完成的委托单和待签批的合同：总览提醒，合同不能签批
        String td = todos(qso);
        assertThat(td).contains("受托厂许可项目没有标注生产条件").contains("眼部护肤类");
        JsonNode demo = read(buyer, "/api/oem-contracts").get("contracts").get(0);
        long demoId = demo.get("id").asLong();
        assertThat(demo.get("licenseGaps").toString()).contains("眼部护肤类");
        mvc.perform(multipart("/api/attachments").file(new MockMultipartFile("file", "合同.pdf", "application/pdf", "%PDF-1.4 contract".getBytes(StandardCharsets.UTF_8)))
                .param("ownerType", "OEM_CONTRACT").param("ownerId", String.valueOf(demoId)).header("Authorization", "Bearer " + buyer)).andExpect(status().isOk());
        List<String> all = List.copyOf(OemContractService.CLAUSES.keySet());
        call(qso, "/api/oem-contracts/" + demoId + "/approve", Map.of("checked", all, "password", PWD)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("没有标注相应生产条件，不能签批")))
                .andExpect(jsonPath("$.message", containsString("（眼部护肤类）")));

        // 自查第 2 项按许可项目标注核对
        long checkId = ok(call(qa, "/api/qms/self-checks", Map.of("periodFrom", today.minusDays(30).toString(), "periodTo", today.toString())))
                .get("check").get("id").asLong();
        Map<Integer, JsonNode> ev = evidence(qa, checkId);
        assertThat(ev.get(2).get("level").asText()).isEqualTo("WARN");
        assertThat(ev.get(2).get("details").toString()).contains("禾川生物（示例）：委托生产").contains("许可项目没有标注具备相应生产条件");
        assertThat(ev.get(2).get("summary").asText()).doesNotContain("许可范围（眼部、儿童护肤类）请人工核对");

        // 供应商勾了也不存
        ObjectNode sup = byCode(admin, "/api/partners", "SUP-HC");
        sup.put("licenseEyeCare", true);
        assertThat(ok(putJson(admin, "/api/partners/" + sup.get("id").asLong(), sup)).get("licenseEyeCare").asBoolean()).isFalse();

        // 受托厂许可证上有标注，在主数据里勾上以后：能签批、能下单，提醒消失
        a3.put("licenseEyeCare", true);
        assertThat(ok(putJson(admin, "/api/partners/" + a3Id, a3)).get("licenseEyeCare").asBoolean()).isTrue();
        assertThat(lastAudit("修改往来单位")).contains("许可标注眼部护肤类 否 → 是");
        a3.remove("licenseEyeCare");
        assertThat(ok(putJson(admin, "/api/partners/" + a3Id, a3)).get("licenseEyeCare").asBoolean()).isTrue();
        assertThat(todos(qso)).doesNotContain("受托厂许可项目没有标注生产条件");
        assertThat(ok(call(qso, "/api/oem-contracts/" + demoId + "/approve", Map.of("checked", all, "password", PWD))).get("status").asText()).isEqualTo("ACTIVE");
        ok(call(prod, "/api/mfg-orders", Map.of("factoryId", a3Id, "fgItemId", serumId, "plannedQty", 100)));
        ev = evidence(qa, checkId);
        assertThat(ev.get(2).get("summary").asText()).contains("已按受托厂许可项目的标注核对");
        assertThat(ev.get(2).get("details").toString()).doesNotContain("许可项目没有标注具备相应生产条件");
        assertThat(ev.get(7).get("details").toString()).contains("许可项目标注具备眼部护肤类生产条件");

        // 精华液再标儿童护肤类：已签批的合同和刚下的委托单都进总览提醒
        ObjectNode serum2 = byCode(admin, "/api/items", "FG-SER-001");
        serum2.put("childCare", true);
        ok(putJson(admin, "/api/items/" + serumId, serum2));
        td = todos(qso);
        assertThat(td).contains("受托厂许可项目没有标注生产条件").contains("儿童护肤类").contains("委托生产合同").contains("委托单");
        assertThat(read(buyer, "/api/oem-contracts").get("contracts").toString()).contains("儿童护肤类");
    }
}
