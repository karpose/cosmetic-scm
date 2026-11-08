package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.SourceType;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
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

/** 标签设计稿评审。用单独的库。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-artwork;MODE=MySQL;DB_CLOSE_DELAY=-1")
class ArtworkFlowTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;
    private static final List<String> ALL = List.of("INGREDIENTS", "FILING", "NET", "USAGE", "WARNING", "COMPANY", "BATCH");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TimeProvider time;
    @Autowired BatchRepository batches;
    @Autowired com.cosmetic.scm.master.PartnerRepository partners;

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

    private long item(String admin, String code, String type) throws Exception {
        Map<String, Object> m = new java.util.HashMap<>(Map.of("code", code, "name", "物料" + code, "itemType", type, "unit", "RM".equals(type) ? "kg" : "个", "banned", false, "active", true, "shelfMonths", 24));
        if ("FG".equals(type)) {
            m.put("netG", 50);
            m.put("filingNo", "备案" + code);
            m.put("unit", "瓶");
        }
        return ok(call(admin, "/api/items", m)).get("id").asLong();
    }

    private void upload(String t, long id) throws Exception {
        upload(t, "LABEL_ARTWORK", id);
    }

    private void upload(String t, String ownerType, long id) throws Exception {
        mvc.perform(multipart("/api/attachments").file(new MockMultipartFile("file", "art.pdf", "application/pdf", "%PDF-1.4 art".getBytes(StandardCharsets.UTF_8)))
                .param("ownerType", ownerType).param("ownerId", String.valueOf(id)).header("Authorization", "Bearer " + t)).andExpect(status().isOk());
    }

    @Test
    void artworkNeedsEveryCheckAFileAnotherQsoAndGoesStaleWhenTheFormulaChanges() throws Exception {
        String admin = login("admin", "Admin@123456");
        String qa = login("qa1", PWD);
        String qso = login("qso1", PWD);
        long rm = item(admin, "AW-RM", "RM");
        long pk = item(admin, "AW-PK", "PK");
        long other = item(admin, "AW-PK2", "PK");
        long fg = item(admin, "AW-FG", "FG");
        mvc.perform(put("/api/boms/" + fg).header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("lines", List.of(
                Map.of("itemId", rm, "qtyPer", new BigDecimal("0.01"), "supplyMode", "BRAND"), Map.of("itemId", pk, "qtyPer", BigDecimal.ONE, "supplyMode", "BRAND")))))).andExpect(status().isOk());

        call(qa, "/api/artworks", Map.of("pkItemId", other, "fgItemId", fg, "changeNote", "x")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不在")));
        call(qa, "/api/artworks", Map.of("pkItemId", rm, "fgItemId", fg, "changeNote", "x")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不是包材")));
        call(login("purchase1", PWD), "/api/artworks", Map.of("pkItemId", pk, "fgItemId", fg, "changeNote", "x")).andExpect(status().isForbidden());
        JsonNode v1 = ok(call(qa, "/api/artworks", Map.of("pkItemId", pk, "fgItemId", fg, "changeNote", "首版")));
        long id1 = v1.get("id").asLong();
        assertThat(v1.get("versionNo").asInt()).isEqualTo(1);
        call(qa, "/api/artworks", Map.of("pkItemId", pk, "fgItemId", fg, "changeNote", "又一版")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("待签批")));
        assertThat(ok(mvc.perform(get("/api/dashboard").header("Authorization", "Bearer " + qso))).get("todos").toString()).contains("标签设计稿待评审");

        // 只有 QSO；评审项要全勾；要有文件
        call(qa, "/api/artworks/" + id1 + "/approve", Map.of("checked", ALL, "password", PWD)).andExpect(status().isForbidden());
        call(qso, "/api/artworks/" + id1 + "/approve", Map.of("checked", List.of("INGREDIENTS"), "password", PWD)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("警示用语")));
        call(qso, "/api/artworks/" + id1 + "/approve", Map.of("checked", ALL, "password", PWD)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("设计稿文件")));
        upload(qa, id1);
        JsonNode a1 = ok(call(qso, "/api/artworks/" + id1 + "/approve", Map.of("checked", ALL, "password", PWD)));
        assertThat(a1.get("status").asText()).isEqualTo("APPROVED");
        assertThat(a1.get("checked").size()).isEqualTo(7);

        // 提交人不能自己批
        long own = ok(call(qso, "/api/artworks", Map.of("pkItemId", pk, "fgItemId", fg, "changeNote", "第二版"))).get("id").asLong();
        upload(qso, own);
        call(qso, "/api/artworks/" + own + "/approve", Map.of("checked", ALL, "password", PWD)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("提交人")));
        ok(call(qso, "/api/artworks/" + own + "/reject", Map.of("note", "换人提交")));

        // 第 3 版批准，第 1 版替代
        long id3 = ok(call(qa, "/api/artworks", Map.of("pkItemId", pk, "fgItemId", fg, "changeNote", "改了警示语"))).get("id").asLong();
        upload(qa, id3);
        ok(call(qso, "/api/artworks/" + id3 + "/approve", Map.of("checked", ALL, "password", PWD)));
        String list = ok(mvc.perform(get("/api/artworks").header("Authorization", "Bearer " + qa))).toString();
        assertThat(list).contains("SUPERSEDED").contains("\"checklist\"");

        // 第 4 版提交后配方变了：第 3 版要重新评审，第 4 版不能批
        long id4 = ok(call(qa, "/api/artworks", Map.of("pkItemId", pk, "fgItemId", fg, "changeNote", "第四版"))).get("id").asLong();
        upload(qa, id4);
        long draft = ok(call(admin, "/api/plm/formulas/" + fg + "/draft", Map.of("changeNote", "调整用量", "lines", List.of(
                Map.of("itemId", rm, "qtyPer", new BigDecimal("0.02"), "supplyMode", "BRAND"), Map.of("itemId", pk, "qtyPer", BigDecimal.ONE, "supplyMode", "BRAND"))))).get("id").asLong();
        ok(call(admin, "/api/plm/versions/" + draft + "/submit", Map.of()));
        long filing = ok(call(qa, "/api/plm/filings", Map.of("fgItemId", fg, "filingType", "GENERAL", "formulaVersionId", draft, "submittedDate", time.today().toString()))).get("id").asLong();
        ok(call(qa, "/api/plm/filings/" + filing + "/complete", Map.of("filingNo", "备案AW-V2", "filedDate", time.today().toString())));
        ok(call(qso, "/api/plm/versions/" + draft + "/approve", Map.of("password", PWD)));
        call(qso, "/api/artworks/" + id4 + "/approve", Map.of("checked", ALL, "password", PWD)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("配方变了")));
        assertThat(ok(mvc.perform(get("/api/dashboard").header("Authorization", "Bearer " + qa))).get("todos").toString()).contains("标签设计稿要重新评审").contains("物料AW-PK");
    }

    private long pkBatch(long pk, String no) {
        return pkBatch(pk, no, null);
    }

    /** supplierId 不为空时当作采购收货的批次。 */
    private long pkBatch(long pk, String no, Long supplierId) {
        Batch b = new Batch();
        b.setBatchNo(no);
        b.setItemId(pk);
        b.setMfgDate(time.today().minusDays(5));
        b.setExpDate(time.today().plusYears(3));
        b.setStatus(BatchStatus.QUARANTINE);
        b.setSourceType(supplierId == null ? SourceType.OPEN : SourceType.PO);
        b.setPartnerId(supplierId);
        b.setSourceNo("AW-TEST");
        b.setQtyIn(java.math.BigDecimal.TEN);
        b.setCreatedAt(time.now());
        return batches.save(b).getId();
    }

    @Test
    void packagingWithAnApprovedArtworkNeedsAPrintCheckBeforeRelease() throws Exception {
        String admin = login("admin", "Admin@123456");
        String qa = login("qa1", PWD);
        String qso = login("qso1", PWD);
        long rm = item(admin, "AWR-RM", "RM");
        long pk = item(admin, "AWR-PK", "PK");
        long plain = item(admin, "AWR-PK2", "PK");
        long fg = item(admin, "AWR-FG", "FG");
        mvc.perform(put("/api/boms/" + fg).header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("lines", List.of(
                Map.of("itemId", rm, "qtyPer", new BigDecimal("0.01"), "supplyMode", "BRAND"), Map.of("itemId", pk, "qtyPer", BigDecimal.ONE, "supplyMode", "BRAND")))))).andExpect(status().isOk());
        long art = ok(call(qa, "/api/artworks", Map.of("pkItemId", pk, "fgItemId", fg, "changeNote", "首版"))).get("id").asLong();
        upload(qa, art);
        ok(call(qso, "/api/artworks/" + art + "/approve", Map.of("checked", ALL, "password", PWD)));
        assertThat(ok(mvc.perform(get("/api/artworks/current/" + pk).header("Authorization", "Bearer " + qa))).get("versionNo").asInt()).isEqualTo(1);
        mvc.perform(get("/api/artworks/current/" + plain).header("Authorization", "Bearer " + qa)).andExpect(status().isNoContent());

        Map<String, Object> rel = new java.util.HashMap<>(Map.of("decision", "RELEASED", "reportNo", "COA", "note", "合格", "password", PWD));
        // 没有设计稿的包材照旧
        ok(call(qa, "/api/quality/batches/" + pkBatch(plain, "AWR-B0") + "/material-decision", rel));
        // 有设计稿：要确认印刷内容
        long b1 = pkBatch(pk, "AWR-B1");
        call(qa, "/api/quality/batches/" + b1 + "/material-decision", rel).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("请确认印刷内容")));
        rel.put("artworkConfirmed", true);
        ok(call(qa, "/api/quality/batches/" + b1 + "/material-decision", rel));
        assertThat(ok(mvc.perform(get("/api/audit?q=AWR-B1").header("Authorization", "Bearer " + admin))).toString()).contains("印刷内容与设计稿").contains("第 1 版");

        // 配方变了：不能放行，拒收照常
        long draft = ok(call(admin, "/api/plm/formulas/" + fg + "/draft", Map.of("changeNote", "调整用量", "lines", List.of(
                Map.of("itemId", rm, "qtyPer", new BigDecimal("0.02"), "supplyMode", "BRAND"), Map.of("itemId", pk, "qtyPer", BigDecimal.ONE, "supplyMode", "BRAND"))))).get("id").asLong();
        ok(call(admin, "/api/plm/versions/" + draft + "/submit", Map.of()));
        long filing = ok(call(qa, "/api/plm/filings", Map.of("fgItemId", fg, "filingType", "GENERAL", "formulaVersionId", draft, "submittedDate", time.today().toString()))).get("id").asLong();
        ok(call(qa, "/api/plm/filings/" + filing + "/complete", Map.of("filingNo", "备案AWR-V2", "filedDate", time.today().toString())));
        ok(call(qso, "/api/plm/versions/" + draft + "/approve", Map.of("password", PWD)));
        long b2 = pkBatch(pk, "AWR-B2");
        call(qa, "/api/quality/batches/" + b2 + "/material-decision", rel).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("重新评审")));
        ok(call(qa, "/api/quality/batches/" + b2 + "/material-decision", Map.of("decision", "REJECTED", "reportNo", "COA", "note", "版本不对", "password", PWD)));
    }

    private static final List<String> PROOF_ALL = List.of("TEXT", "COLOR", "CODE", "SIZE", "MATERIAL", "PRINT_AREA");

    @Test
    void printedPackagingFromASupplierNeedsItsSignedProofAndBatchesRememberTheVersion() throws Exception {
        String admin = login("admin", "Admin@123456");
        String qa = login("qa1", PWD);
        String qso = login("qso1", PWD);
        String buyer = login("purchase1", PWD);
        long rm = item(admin, "AWP-RM", "RM");
        long pk = item(admin, "AWP-PK", "PK");
        long fg = item(admin, "AWP-FG", "FG");
        mvc.perform(put("/api/boms/" + fg).header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("lines", List.of(
                Map.of("itemId", rm, "qtyPer", new BigDecimal("0.01"), "supplyMode", "BRAND"), Map.of("itemId", pk, "qtyPer", BigDecimal.ONE, "supplyMode", "BRAND")))))).andExpect(status().isOk());
        long art = ok(call(qa, "/api/artworks", Map.of("pkItemId", pk, "fgItemId", fg, "changeNote", "首版"))).get("id").asLong();
        // 没批准的设计稿不能打样
        call(buyer, "/api/artworks/" + art + "/proofs", Map.of("supplierId", 1, "proofDate", time.today().toString())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("只有已批准的现行版本")));
        upload(qa, art);
        ok(call(qso, "/api/artworks/" + art + "/approve", Map.of("checked", ALL, "password", PWD)));

        var sups = partners.findAll().stream().filter(x -> x.getKind() == com.cosmetic.scm.domain.PartnerKind.SUPPLIER).toList();
        long s1 = sups.get(0).getId();
        long s2 = sups.get(1).getId();
        long customer = partners.findAll().stream().filter(x -> x.getKind() == com.cosmetic.scm.domain.PartnerKind.CUSTOMER).findFirst().orElseThrow().getId();
        String today = time.today().toString();
        call(buyer, "/api/artworks/" + art + "/proofs", Map.of("supplierId", customer, "proofDate", today)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("不是供应商")));
        call(buyer, "/api/artworks/" + art + "/proofs", Map.of("supplierId", s1, "proofDate", time.today().plusDays(1).toString())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("不能晚于今天")));
        call(login("prod1", PWD), "/api/artworks/" + art + "/proofs", Map.of("supplierId", s1, "proofDate", today)).andExpect(status().isForbidden());
        JsonNode p1 = ok(call(buyer, "/api/artworks/" + art + "/proofs", Map.of("supplierId", s1, "proofDate", today, "note", "首次打样")));
        assertThat(p1.get("roundNo").asInt()).isEqualTo(1);
        assertThat(p1.get("status").asText()).isEqualTo("PENDING");
        call(buyer, "/api/artworks/" + art + "/proofs", Map.of("supplierId", s1, "proofDate", today)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("已有一次打样待签样")));
        assertThat(ok(mvc.perform(get("/api/dashboard").header("Authorization", "Bearer " + qa))).toString()).contains("包材打样待签样").contains(p1.get("proofNo").asText());

        // 供应商收货的批次：没签样不能放行；期初导入的批次只核对印刷内容
        Map<String, Object> rel = new java.util.HashMap<>(Map.of("decision", "RELEASED", "reportNo", "COA", "note", "合格", "password", PWD, "artworkConfirmed", true));
        long b1 = pkBatch(pk, "AWP-B1", s1);
        JsonNode chk = ok(mvc.perform(get("/api/artworks/release-check/" + b1).header("Authorization", "Bearer " + qa)));
        assertThat(chk.get("proofRequired").asBoolean()).isTrue();
        assertThat(chk.get("proof").isNull()).isTrue();
        call(qa, "/api/quality/batches/" + b1 + "/material-decision", rel).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("还没有设计稿")))
                .andExpect(jsonPath("$.message", containsString("的签样")));
        long open = pkBatch(pk, "AWP-B0");
        assertThat(ok(mvc.perform(get("/api/artworks/release-check/" + open).header("Authorization", "Bearer " + qa))).get("proofRequired").asBoolean()).isFalse();
        ok(call(qa, "/api/quality/batches/" + open + "/material-decision", rel));
        assertThat(batches.findById(open).orElseThrow().getArtworkId()).isEqualTo(art);
        assertThat(batches.findById(open).orElseThrow().getProofId()).isNull();

        // 签样：逐项核对、要有样张、签样人不能是登记人
        long p1id = p1.get("id").asLong();
        call(qa, "/api/artworks/proofs/" + p1id + "/approve", Map.of("checked", PROOF_ALL.subList(0, 5), "password", PWD)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("喷印位置")));
        call(qa, "/api/artworks/proofs/" + p1id + "/approve", Map.of("checked", PROOF_ALL, "password", PWD)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("样张")));
        upload(buyer, "ARTWORK_PROOF", p1id);
        call(buyer, "/api/artworks/proofs/" + p1id + "/approve", Map.of("checked", PROOF_ALL, "password", PWD)).andExpect(status().isForbidden());
        JsonNode signed = ok(call(qa, "/api/artworks/proofs/" + p1id + "/approve", Map.of("checked", PROOF_ALL, "password", PWD)));
        assertThat(signed.get("status").asText()).isEqualTo("APPROVED");
        assertThat(signed.get("current").asBoolean()).isTrue();

        JsonNode p2 = ok(call(qa, "/api/artworks/" + art + "/proofs", Map.of("supplierId", s2, "proofDate", today)));
        upload(qa, "ARTWORK_PROOF", p2.get("id").asLong());
        call(qa, "/api/artworks/proofs/" + p2.get("id").asLong() + "/approve", Map.of("checked", PROOF_ALL, "password", PWD)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("签样人不能是登记人")));
        ok(call(qso, "/api/artworks/proofs/" + p2.get("id").asLong() + "/reject", Map.of("note", "颜色偏红")));
        assertThat(ok(call(qa, "/api/artworks/" + art + "/proofs", Map.of("supplierId", s2, "proofDate", today))).get("roundNo").asInt()).isEqualTo(2);

        // 有签样：放行，批次记下设计稿和签样
        ok(call(qa, "/api/quality/batches/" + b1 + "/material-decision", rel));
        Batch released = batches.findById(b1).orElseThrow();
        assertThat(released.getArtworkId()).isEqualTo(art);
        assertThat(released.getProofId()).isEqualTo(p1id);
        assertThat(ok(mvc.perform(get("/api/audit?q=AWP-B1").header("Authorization", "Bearer " + admin))).toString()).contains("签样 " + p1.get("proofNo").asText());
        // 别家供应商的批次不能凭这份签样放行
        call(qa, "/api/quality/batches/" + pkBatch(pk, "AWP-B2", s2) + "/material-decision", rel).andExpect(status().isBadRequest());

        // 再次打样签样，上一份替代；作废后要重新签样
        JsonNode p3 = ok(call(buyer, "/api/artworks/" + art + "/proofs", Map.of("supplierId", s1, "proofDate", today, "note", "换承印物")));
        upload(buyer, "ARTWORK_PROOF", p3.get("id").asLong());
        ok(call(qso, "/api/artworks/proofs/" + p3.get("id").asLong() + "/approve", Map.of("checked", PROOF_ALL, "password", PWD)));
        JsonNode detail = ok(mvc.perform(get("/api/artworks/" + art).header("Authorization", "Bearer " + login("sales1", PWD))));
        assertThat(detail.get("proofChecklist").size()).isEqualTo(6);
        assertThat(detail.get("proofs").toString()).contains("\"status\":\"SUPERSEDED\"").contains("\"status\":\"REJECTED\"");
        assertThat(detail.get("batches").toString()).contains("AWP-B1").contains("AWP-B0").contains(p1.get("proofNo").asText());
        call(qa, "/api/artworks/proofs/" + p3.get("id").asLong() + "/void", Map.of("note", "错")).andExpect(status().isForbidden());
        ok(call(qso, "/api/artworks/proofs/" + p3.get("id").asLong() + "/void", Map.of("note", "发现条码印错")));
        call(qa, "/api/artworks/proofs/" + p3.get("id").asLong() + "/void", Map.of("note", "再作废")).andExpect(status().isForbidden());
        call(qso, "/api/artworks/proofs/" + p3.get("id").asLong() + "/void", Map.of("note", "再作废")).andExpect(status().isBadRequest());
        call(qa, "/api/quality/batches/" + pkBatch(pk, "AWP-B3", s1) + "/material-decision", rel).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("的签样")));

        // 换版：旧版不能再打样，旧版的签样不再是现行；按旧版放行的批次还能查到
        long art2 = ok(call(qa, "/api/artworks", Map.of("pkItemId", pk, "fgItemId", fg, "changeNote", "改警示用语"))).get("id").asLong();
        upload(qa, art2);
        ok(call(qso, "/api/artworks/" + art2 + "/approve", Map.of("checked", ALL, "password", PWD)));
        call(buyer, "/api/artworks/" + art + "/proofs", Map.of("supplierId", s1, "proofDate", today)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("已替代")));
        JsonNode old = ok(mvc.perform(get("/api/artworks/" + art).header("Authorization", "Bearer " + qa)));
        assertThat(old.get("proofs").toString()).doesNotContain("\"current\":true");
        assertThat(old.get("batches").size()).isEqualTo(2);
        assertThat(ok(mvc.perform(get("/api/audit?q=" + p3.get("proofNo").asText()).header("Authorization", "Bearer " + admin))).toString()).contains("作废包材签样").contains("发现条码印错");
    }
}
