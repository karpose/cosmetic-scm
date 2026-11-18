package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.Role;
import com.cosmetic.scm.domain.StatementStatus;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.master.ItemRepository;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.PartnerRepository;
import com.cosmetic.scm.master.WarehouseRepository;
import com.cosmetic.scm.security.UserRepository;
import com.cosmetic.scm.security.UserService;
import com.cosmetic.scm.settlement.SupplierStatementRepository;
import com.cosmetic.scm.sourcing.RfqRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** 询价比价和对账结算：封标、隔离、职责分离，金额手算。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SourcingSettlementTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PartnerRepository partners;
    @Autowired ItemRepository items;
    @Autowired WarehouseRepository warehouses;
    @Autowired BatchRepository batches;
    @Autowired UserRepository users;
    @Autowired UserService userService;
    @Autowired TimeProvider time;
    @Autowired RfqRepository rfqs;
    @Autowired SupplierStatementRepository statements;

    private LocalDate d(int n) {
        return time.today().plusDays(n);
    }

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

    private ResultActions read(String t, String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", "Bearer " + t));
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String text(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private Long item(String code) {
        return items.findAll().stream().filter(i -> code.equals(i.getCode())).findFirst().orElseThrow().getId();
    }

    /** 新建一家供应商和它的门户账号，金额不受示例数据影响。 */
    private Long supplier(String code, String username) {
        Partner p = partners.findByCode(code).orElseGet(() -> {
            Partner n = new Partner();
            n.setCode(code);
            n.setName(code + " 供应商");
            n.setKind(PartnerKind.SUPPLIER);
            return partners.save(n);
        });
        if (!users.existsByUsername(username)) {
            userService.newUser(username, username, PWD, EnumSet.of(Role.SUPPLIER_PORTAL), p.getId(), false);
        }
        return p.getId();
    }

    private Map<String, Object> quote(long lineId, String price) {
        return Map.of("rfqLineId", lineId, "unitPrice", new BigDecimal(price), "taxRate", new BigDecimal("13"), "leadDays", 5, "validUntil", d(30).toString());
    }

    @Test
    void quotesStaySealedUntilOpenAndOnlyBuyersAndFinanceSeePrices() throws Exception {
        Long a = supplier("SUP-RFQ-A", "rfq_a");
        Long b = supplier("SUP-RFQ-B", "rfq_b");
        Long c = supplier("SUP-RFQ-C", "rfq_c");
        String buyer = login("purchase1");
        JsonNode rfq = body(call(buyer, "/api/rfqs", Map.of("title", "甘油询价", "deadline", d(7).toString(), "supplierIds", List.of(a, b),
                "lines", List.of(Map.of("itemId", item("RM-GLY-002"), "qty", 100)))).andExpect(status().isOk()));
        long id = rfq.get("id").asLong();
        long line = rfq.get("lines").get(0).get("id").asLong();

        // 其它内部岗位进不来
        for (String u : List.of("viewer1", "qa1", "prod1", "sales1")) {
            read(login(u), "/api/rfqs").andExpect(status().isForbidden());
            read(login(u), "/api/statements").andExpect(status().isForbidden());
        }
        read(login("viewer1"), "/api/reports/statements").andExpect(status().isForbidden());

        // 没被邀请的看不到也报不了
        String sc = login("rfq_c");
        assertThat(body(read(sc, "/api/portal/supplier/rfqs")).size()).isEqualTo(0);
        call(sc, "/api/portal/supplier/rfqs/" + id + "/quote", Map.of("lines", List.of(quote(line, "1")))).andExpect(status().isNotFound());

        String sa = login("rfq_a");
        String sb = login("rfq_b");
        call(sa, "/api/portal/supplier/rfqs/" + id + "/quote", Map.of("lines", List.of(quote(line, "87654.3")))).andExpect(status().isOk());

        // 开标前内部拿不到价格：接口不返回，审计也不记
        String sealed = text(read(buyer, "/api/rfqs").andExpect(status().isOk()));
        assertThat(sealed).doesNotContain("87654.3");
        assertThat(text(read(login("admin", "Admin@123456"), "/api/audit?limit=300"))).doesNotContain("87654.3");
        // 还有人没报，不能提前开标
        call(buyer, "/api/rfqs/" + id + "/open", Map.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("还没报完")));

        // 截止前可以改价
        call(sa, "/api/portal/supplier/rfqs/" + id + "/quote", Map.of("lines", List.of(quote(line, "12")))).andExpect(status().isOk());
        // 有效期不能早于截止日期
        var early = new HashMap<>(quote(line, "12"));
        early.put("validUntil", d(1).toString());
        call(sa, "/api/portal/supplier/rfqs/" + id + "/quote", Map.of("lines", List.of(early))).andExpect(status().isBadRequest());
        call(sb, "/api/portal/supplier/rfqs/" + id + "/quote", Map.of("lines", List.of(quote(line, "10")))).andExpect(status().isOk());
        // 看不到别家的报价
        String seenByA = text(read(sa, "/api/portal/supplier/rfqs"));
        assertThat(seenByA).contains("12").doesNotContain("SUP-RFQ-B").doesNotContain("rfq_b");
        assertThat(body(read(sa, "/api/portal/supplier/rfqs")).get(0).get("lines").get(0).get("myQuote").get("unitPrice").decimalValue()).isEqualByComparingTo("12");

        // 都报完可以提前开标，开标后不能再报
        JsonNode opened = body(call(buyer, "/api/rfqs/" + id + "/open", Map.of()).andExpect(status().isOk()));
        assertThat(opened.get("sealed").asBoolean()).isFalse();
        JsonNode qs = opened.get("lines").get(0).get("quotes");
        assertThat(qs.size()).isEqualTo(2);
        assertThat(qs.get(0).get("supplierId").asLong()).isEqualTo(b); // 按价格排序
        assertThat(qs.get(0).get("lowest").asBoolean()).isTrue();
        assertThat(qs.get(1).get("amount").decimalValue()).isEqualByComparingTo("1200");
        call(sa, "/api/portal/supplier/rfqs/" + id + "/quote", Map.of("lines", List.of(quote(line, "1")))).andExpect(status().isBadRequest());

        // 定标人不能是询价人
        Map<String, Object> pickB = new HashMap<>(Map.of("lines", List.of(Map.of("rfqLineId", line, "supplierId", b)), "password", PWD, "reason", "只有两家"));
        call(buyer, "/api/rfqs/" + id + "/award", pickB).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不能是询价人")));
        String admin = login("admin", "Admin@123456");
        // 签批密码、缺原因、没报的供应商
        call(admin, "/api/rfqs/" + id + "/award", Map.of("lines", List.of(Map.of("rfqLineId", line, "supplierId", b)), "password", "wrong", "reason", "x")).andExpect(status().isBadRequest());
        call(admin, "/api/rfqs/" + id + "/award", Map.of("lines", List.of(Map.of("rfqLineId", line, "supplierId", b)), "password", "Admin@123456"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("定标原因")));
        call(admin, "/api/rfqs/" + id + "/award", Map.of("lines", List.of(Map.of("rfqLineId", line, "supplierId", c)), "password", "Admin@123456", "reason", "x")).andExpect(status().isBadRequest());
        // 定标生成带价格的采购单
        JsonNode awarded = body(call(admin, "/api/rfqs/" + id + "/award", Map.of("lines", List.of(Map.of("rfqLineId", line, "supplierId", b)), "password", "Admin@123456", "reason", "只有两家")).andExpect(status().isOk()));
        assertThat(awarded.get("status").asText()).isEqualTo("AWARDED");
        String poNo = awarded.get("lines").get(0).get("quotes").get(0).get("poNo").asText();
        assertThat(poNo).startsWith("PO-");
        call(admin, "/api/rfqs/" + id + "/award", Map.of("lines", List.of(Map.of("rfqLineId", line, "supplierId", b)), "password", "Admin@123456", "reason", "x")).andExpect(status().isBadRequest());

        // 单价只给采购和财务：查询接口按角色隐藏，供应商看自己的
        JsonNode buyerPo = poByNo(buyer, poNo);
        assertThat(buyerPo.get("lines").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("10");
        assertThat(poByNo(login("finance1"), poNo).get("lines").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("10");
        assertThat(poByNo(login("qa1"), poNo).get("lines").get(0).get("unitPrice").isNull()).isTrue();
        assertThat(poByNo(login("viewer1"), poNo).get("lines").get(0).get("unitPrice").isNull()).isTrue();

        // 结果：中标的知道，没中的只知道没中
        assertThat(body(read(sb, "/api/portal/supplier/rfqs")).get(0).get("stateLabel").asText()).isEqualTo("已中标");
        assertThat(body(read(sa, "/api/portal/supplier/rfqs")).get(0).get("stateLabel").asText()).isEqualTo("未中标");
        assertThat(text(read(sa, "/api/portal/supplier/rfqs"))).doesNotContain(poNo).doesNotContain("只有两家");
    }

    @Test
    void onlyQuotesSubmittedForTheCurrentRoundCanWin() throws Exception {
        Long a = supplier("SUP-ROUND-A", "round_a");
        Long b = supplier("SUP-ROUND-B", "round_b");
        String buyer = login("purchase1");
        String sa = login("round_a");
        String sb = login("round_b");
        String admin = login("admin", "Admin@123456");
        JsonNode rfq = body(call(buyer, "/api/rfqs", Map.of("title", "多轮议价",
                "deadline", d(7), "supplierIds", List.of(a, b), "lines", List.of(
                        Map.of("itemId", item("RM-GLY-002"), "qty", 2),
                        Map.of("itemId", item("RM-PHE-004"), "qty", 3)))).andExpect(status().isOk()));
        long id = rfq.get("id").asLong();
        long firstLine = rfq.get("lines").get(0).get("id").asLong();
        long secondLine = rfq.get("lines").get(1).get("id").asLong();
        String internal = "/api/rfqs/" + id;
        String portal = "/api/portal/supplier/rfqs/" + id + "/quote";
        call(sa, portal, Map.of("roundNo", 1, "lines", List.of(quote(firstLine, "17"), quote(secondLine, "19"))))
                .andExpect(status().isOk());
        call(sb, portal, Map.of("roundNo", 1, "lines", List.of(quote(firstLine, "11"), quote(secondLine, "13"))))
                .andExpect(status().isOk());
        call(buyer, internal + "/open", Map.of()).andExpect(status().isOk());

        call(buyer, internal + "/next-round", Map.of("roundNo", 1, "deadline", d(40), "note", "按年采量重新报价"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].quotes").isEmpty())
                .andExpect(jsonPath("$.suppliers[0].quotedLines").value(0));
        read(sb, "/api/portal/supplier/rfqs").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].lines[0].myQuote.roundNo").value(1))
                .andExpect(jsonPath("$[0].lines[0].myQuote.validUntil").value(d(30).toString()));
        call(sa, portal, Map.of("roundNo", 1, "lines", List.of(quote(firstLine, "15"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("轮次已变更")));
        var current = new HashMap<>(quote(firstLine, "16"));
        current.put("validUntil", d(45));
        call(sa, portal, Map.of("roundNo", 2, "lines", List.of(current))).andExpect(status().isOk());
        current.put("unitPrice", new BigDecimal("15"));
        call(sa, portal, Map.of("roundNo", 2, "lines", List.of(current))).andExpect(status().isOk());
        read(buyer, "/api/rfqs").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].suppliers[0].quotedLines").value(1))
                .andExpect(jsonPath("$[0].lines[0].quotes").isEmpty());
        call(buyer, internal + "/open", Map.of()).andExpect(status().isBadRequest());

        // 模拟新一轮截止，只有实际提交的报价参与比价。
        var ended = rfqs.findById(id).orElseThrow();
        ended.setDeadline(d(-1));
        rfqs.save(ended);
        read(buyer, "/api/rfqs").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].lines[0].quotes.length()").value(1))
                .andExpect(jsonPath("$[0].lines[0].quotes[0].prevUnitPrice").value(17.0))
                .andExpect(jsonPath("$[0].lines[1].quotes").isEmpty());
        call(admin, internal + "/award", Map.of("roundNo", 2, "password", "Admin@123456", "reason", "只有一家",
                "lines", List.of(Map.of("rfqLineId", firstLine, "supplierId", b))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("本轮未提交")));
        call(admin, internal + "/award", Map.of("roundNo", 2, "password", "Admin@123456", "reason", "只有一家",
                "lines", List.of(Map.of("rfqLineId", firstLine, "supplierId", a))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AWARDED"))
                .andExpect(jsonPath("$.lines[0].quotes[0].unitPrice").value(15.0))
                .andExpect(jsonPath("$.lines[0].quotes[0].poNo").isNotEmpty());
    }

    @Test
    void cancellingAnUnopenedRfqDoesNotRevealQuotes() throws Exception {
        Long supplier = supplier("SUP-RFQ-CANCEL", "rfq_cancel");
        String buyer = login("purchase1");
        JsonNode rfq = body(call(buyer, "/api/rfqs", Map.of("title", "取消未开标的询价",
                "deadline", d(7), "supplierIds", List.of(supplier),
                "lines", List.of(Map.of("itemId", item("RM-GLY-002"), "qty", 2)))).andExpect(status().isOk()));
        long id = rfq.get("id").asLong();
        long line = rfq.get("lines").get(0).get("id").asLong();
        call(login("rfq_cancel"), "/api/portal/supplier/rfqs/" + id + "/quote", Map.of("lines", List.of(quote(line, "33"))))
                .andExpect(status().isOk());
        call(buyer, "/api/rfqs/" + id + "/cancel", Map.of("reason", "采购需求取消"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].quotes").isEmpty());
        read(buyer, "/api/rfqs").andExpect(status().isOk()).andExpect(jsonPath("$[0].lines[0].quotes").isEmpty());
    }

    @Autowired
    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mappings;

    /** xlsx 是压缩包，要解开才能搜内容。 */
    private static String readable(byte[] body) throws java.io.IOException {
        if (body.length > 4 && body[0] == 'P' && body[1] == 'K') {
            StringBuilder out = new StringBuilder();
            try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(body))) {
                while (z.getNextEntry() != null) {
                    out.append(new String(z.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            return out.toString();
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    /**
     * 价格和金额只给采购、财务、管理员。这里不靠记性列接口：把所有没有路径参数的 GET 都扫一遍，
     * 用不会重复的数字当记号，只读和质量账号哪里都不能看到。新加的接口自动被扫到。
     */
    @Test
    void pricesAndAmountsDoNotLeakToOtherInternalRoles() throws Exception {
        Long s = supplier("SUP-LEAK", "leak_s");
        String buyer = login("purchase1");
        // 价格 7391.2377，税 13%：3 × = 22173.71（四舍五入），税额 2550.91
        List<Batch> b = buyAndReceive(buyer, s, "RM-GLY-002", "3", "7391.2377", "13");
        setStatus(b.get(0), BatchStatus.RELEASED);
        long poId = poByNo(buyer, b.get(0).getSourceNo()).get("id").asLong();
        long poLine = poByNo(buyer, b.get(0).getSourceNo()).get("lines").get(0).get("id").asLong();
        // 改一次价，审计里会有前后值
        call(buyer, "/api/purchase-orders/" + poId + "/prices", Map.of("reason", "议价", "lines", List.of(Map.of("poLineId", poLine, "unitPrice", new BigDecimal("7391.2388"), "taxRate", 13)))).andExpect(status().isOk());
        call(buyer, "/api/purchase-orders/" + poId + "/prices", Map.of("reason", "议价", "lines", List.of(Map.of("poLineId", poLine, "unitPrice", new BigDecimal("7391.2377"), "taxRate", 13)))).andExpect(status().isOk());
        JsonNode st = body(call(buyer, "/api/statements", Map.of("supplierId", s, "periodTo", d(0).toString())).andExpect(status().isOk()));
        long sid = st.get("id").asLong();
        assertThat(st.get("totalAmount").decimalValue()).isEqualByComparingTo("22173.71");
        String sup = login("leak_s");
        call(sup, "/api/portal/supplier/statements/" + sid + "/confirm", Map.of()).andExpect(status().isOk());
        call(sup, "/api/portal/supplier/statements/" + sid + "/invoice", Map.of("invoiceNo", "LEAK-1", "invoiceDate", d(0).toString(), "amount", new BigDecimal("22173.71"))).andExpect(status().isOk());
        // 财务上传的发票扫描件
        String fin = login("finance1");
        long att = body(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/attachments")
                .file(new org.springframework.mock.web.MockMultipartFile("file", "发票扫描件-7391.pdf", "application/pdf", "%PDF-1.4 x".getBytes(StandardCharsets.ISO_8859_1)))
                .param("ownerType", "SUPPLIER_STATEMENT").param("ownerId", String.valueOf(sid)).header("Authorization", "Bearer " + fin)).andExpect(status().isOk())).get("id").asLong();

        List<String> markers = List.of("7391.2377", "7391.2388", "22173.71", "2550.91", "发票扫描件-7391");
        List<String> urls = new ArrayList<>();
        mappings.getHandlerMethods().forEach((info, h) -> {
            boolean isGet = info.getMethodsCondition().getMethods().contains(org.springframework.web.bind.annotation.RequestMethod.GET);
            info.getPatternValues().forEach(p -> {
                if (isGet && p.startsWith("/api/") && !p.contains("{") && !p.startsWith("/api/portal") && !p.startsWith("/api/integration") && !p.startsWith("/api/public")) {
                    urls.add(p);
                }
            });
        });
        registryReports(urls);
        urls.add("/api/purchase-orders/" + poId);
        urls.add("/api/attachments?ownerType=SUPPLIER_STATEMENT&ownerId=" + sid);
        urls.add("/api/attachments/" + att + "/download");
        urls.add("/api/audit?limit=300");
        urls.add("/api/trace/" + b.get(0).getBatchNo());
        assertThat(urls.size()).isGreaterThan(30);

        List<String> leaks = new ArrayList<>();
        int scanned = 0;
        for (String u : List.of("viewer1", "qa1", "prod1", "sales1")) {
            String token = login(u);
            for (String url : urls) {
                var r = mvc.perform(get(url).header("Authorization", "Bearer " + token)).andReturn().getResponse();
                if (r.getStatus() != 200) {
                    continue;
                }
                scanned++;
                String text = readable(r.getContentAsByteArray());
                for (String m : markers) {
                    if (text.contains(m)) {
                        leaks.add(u + " " + url + " 含 " + m);
                    }
                }
            }
        }
        assertThat(scanned).isGreaterThan(100);
        assertThat(leaks).isEmpty();
        // 文件内容里没有记号，要单独查：发票文件下载不了，也传不上去
        String viewer = login("viewer1");
        read(viewer, "/api/attachments/" + att + "/download").andExpect(status().isForbidden());
        read(login("qa1"), "/api/attachments?ownerType=SUPPLIER_STATEMENT&ownerId=" + sid).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/attachments")
                .file(new org.springframework.mock.web.MockMultipartFile("file", "x.pdf", "application/pdf", "%PDF-1.4 x".getBytes(StandardCharsets.ISO_8859_1)))
                .param("ownerType", "SUPPLIER_STATEMENT").param("ownerId", String.valueOf(sid)).header("Authorization", "Bearer " + login("qa1"))).andExpect(status().isForbidden());
        // 搜索不能反推金额：没权限的人搜数字搜不到，有权限的搜得到
        assertThat(body(read(viewer, "/api/audit?q=7391.2377")).size()).isEqualTo(0);
        assertThat(body(read(viewer, "/api/audit?q=22173.71")).size()).isEqualTo(0);
        assertThat(body(read(fin, "/api/audit?q=7391.2377")).size()).isGreaterThan(0);
        // 遮盖只影响读取：操作本身还查得到，审计原文没有被改
        String masked = text(read(viewer, "/api/audit?q=" + st.get("statementNo").asText()));
        assertThat(masked).contains("生成对账单").contains("含金额").doesNotContain("22173.71");
        assertThat(text(read(fin, "/api/audit?q=" + st.get("statementNo").asText()))).contains("22173.71");
        // 有权限的人确实看得到，上面不是因为数据没进去才通过的
        assertThat(text(read(buyer, "/api/purchase-orders/" + poId))).contains("7391.2377");
    }

    private void registryReports(List<String> urls) {
        for (String key : List.of("stock", "batches", "purchases", "production", "shipments", "complaints", "deviations", "capas", "supplier-audits", "retains", "equipment", "statements", "supplier-returns", "stability", "inspections")) {
            urls.add("/api/reports/" + key);
        }
        urls.add("/api/reports/audit?from=" + d(-1) + "&to=" + d(1));
    }

    @Autowired com.cosmetic.scm.sourcing.RfqRepository rfqRepo;

    @Test
    void lineWithoutQuotesDoesNotBlockTheRest() throws Exception {
        Long a = supplier("SUP-RFQ-P", "rfq_p");
        String buyer = login("purchase1");
        JsonNode rfq = body(call(buyer, "/api/rfqs", Map.of("title", "两行询价", "deadline", d(3).toString(), "supplierIds", List.of(a),
                "lines", List.of(Map.of("itemId", item("RM-GLY-002"), "qty", 10), Map.of("itemId", item("RM-PHE-004"), "qty", 5)))).andExpect(status().isOk()));
        long id = rfq.get("id").asLong();
        long l1 = rfq.get("lines").get(0).get("id").asLong();
        // 只报了第一行：没报完不能提前开标，到了截止日期才能开
        call(login("rfq_p"), "/api/portal/supplier/rfqs/" + id + "/quote", Map.of("lines", List.of(quote(l1, "10")))).andExpect(status().isOk());
        call(buyer, "/api/rfqs/" + id + "/open", Map.of()).andExpect(status().isBadRequest());
        var r = rfqRepo.findById(id).orElseThrow();
        r.setDeadline(d(-1));
        rfqRepo.save(r);
        String admin = login("admin", "Admin@123456");
        // 只定第一行，第二行没人报。曾经要求每行都选，这种单子就卡死了，只能整张取消
        JsonNode done = body(call(admin, "/api/rfqs/" + id + "/award", Map.of("lines", List.of(Map.of("rfqLineId", l1, "supplierId", a)), "password", "Admin@123456", "reason", "只有一家")).andExpect(status().isOk()));
        assertThat(done.get("status").asText()).isEqualTo("AWARDED");
        assertThat(poByNo(buyer, done.get("lines").get(0).get("quotes").get(0).get("poNo").asText()).get("lines").size()).isEqualTo(1);
        // 一行都不定不行；不属于这张单的行不行
        JsonNode other = body(call(buyer, "/api/rfqs", Map.of("title", "x", "deadline", d(3).toString(), "supplierIds", List.of(a), "lines", List.of(Map.of("itemId", item("RM-GLY-002"), "qty", 1)))).andExpect(status().isOk()));
        long oid = other.get("id").asLong();
        var o = rfqRepo.findById(oid).orElseThrow();
        o.setDeadline(d(-1));
        rfqRepo.save(o);
        call(admin, "/api/rfqs/" + oid + "/award", Map.of("lines", List.of(), "password", "Admin@123456")).andExpect(status().isBadRequest());
        call(admin, "/api/rfqs/" + oid + "/award", Map.of("lines", List.of(Map.of("rfqLineId", l1, "supplierId", a)), "password", "Admin@123456", "reason", "x")).andExpect(status().isBadRequest());
    }

    @Test
    void cannotApproveOrPayWhenABatchIsNoLongerReleased() throws Exception {
        Long s = supplier("SUP-SET-F", "set_f");
        String buyer = login("purchase1");
        List<Batch> b = buyAndReceive(buyer, s, "RM-GLY-002", "2", "50", "0");
        setStatus(b.get(0), BatchStatus.RELEASED);
        long id = body(call(buyer, "/api/statements", Map.of("supplierId", s, "periodTo", d(0).toString())).andExpect(status().isOk())).get("id").asLong();
        String sup = login("set_f");
        call(sup, "/api/portal/supplier/statements/" + id + "/confirm", Map.of()).andExpect(status().isOk());
        call(sup, "/api/portal/supplier/statements/" + id + "/invoice", Map.of("invoiceNo", "INV-F", "invoiceDate", d(0).toString(), "amount", 100)).andExpect(status().isOk());
        String fin = login("finance1");
        // 生成之后才发现问题：批次被冻结
        setStatus(b.get(0), BatchStatus.FROZEN);
        call(fin, "/api/statements/" + id + "/approve", Map.of("password", PWD)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString(b.get(0).getBatchNo())));
        // 解冻后可以审批；付款前又判不合格，也不能付
        setStatus(b.get(0), BatchStatus.RELEASED);
        call(fin, "/api/statements/" + id + "/approve", Map.of("password", PWD)).andExpect(status().isOk());
        setStatus(b.get(0), BatchStatus.REJECTED);
        call(fin, "/api/statements/" + id + "/pay", Map.of("payDate", d(0).toString(), "amount", 100)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("不合格")));
        setStatus(b.get(0), BatchStatus.RELEASED);
        call(fin, "/api/statements/" + id + "/pay", Map.of("payDate", d(0).toString(), "amount", 100)).andExpect(status().isOk());
    }

    private JsonNode poByNo(String token, String poNo) throws Exception {
        for (JsonNode p : body(read(token, "/api/purchase-orders").andExpect(status().isOk()))) {
            if (poNo.equals(p.get("poNo").asText())) {
                return p;
            }
        }
        throw new AssertionError(poNo);
    }

    @Test
    void cannotInviteUnqualifiedSupplierOrBannedItem() throws Exception {
        Long a = supplier("SUP-RFQ-BAD", "rfq_bad");
        Partner p = partners.findById(a).orElseThrow();
        p.setQualified(false);
        partners.save(p);
        String buyer = login("purchase1");
        call(buyer, "/api/rfqs", Map.of("title", "x", "deadline", d(1).toString(), "supplierIds", List.of(a), "lines", List.of(Map.of("itemId", item("RM-GLY-002"), "qty", 1))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不在合格名录")));
        p.setQualified(true);
        partners.save(p);
        call(buyer, "/api/rfqs", Map.of("title", "x", "deadline", d(-1).toString(), "supplierIds", List.of(a), "lines", List.of(Map.of("itemId", item("RM-GLY-002"), "qty", 1))))
                .andExpect(status().isBadRequest());
    }

    /** 一张采购单收货一批，放行后才能结算。返回批次。 */
    private List<Batch> buyAndReceive(String buyer, Long supplier, String... codeQtyPriceTax) throws Exception {
        List<Map<String, Object>> ls = new ArrayList<>();
        for (int i = 0; i < codeQtyPriceTax.length; i += 4) {
            Map<String, Object> l = new HashMap<>(Map.of("itemId", item(codeQtyPriceTax[i]), "qty", new BigDecimal(codeQtyPriceTax[i + 1])));
            if (!codeQtyPriceTax[i + 2].isEmpty()) {
                l.put("unitPrice", new BigDecimal(codeQtyPriceTax[i + 2]));
                l.put("taxRate", new BigDecimal(codeQtyPriceTax[i + 3]));
            }
            ls.add(l);
        }
        JsonNode po = body(call(buyer, "/api/purchase-orders", Map.of("supplierId", supplier, "lines", ls)).andExpect(status().isOk()));
        long wh = warehouses.findAll().stream().filter(w -> "WH-MAIN".equals(w.getCode())).findFirst().orElseThrow().getId();
        List<Map<String, Object>> recv = new ArrayList<>();
        for (JsonNode l : po.get("lines")) {
            recv.add(Map.of("poLineId", l.get("id").asLong(), "qty", l.get("qty").decimalValue(), "extBatchNo", "EXT-" + l.get("id").asLong(), "warehouseId", wh));
        }
        JsonNode r = body(call(buyer, "/api/purchase-orders/" + po.get("id").asLong() + "/receipts", Map.of("lines", recv)).andExpect(status().isOk()));
        List<Batch> out = new ArrayList<>();
        for (JsonNode no : r.get("batchNos")) {
            out.add(batches.findByBatchNo(no.asText()).orElseThrow());
        }
        return out;
    }

    private void setStatus(Batch b, BatchStatus s) {
        b.setStatus(s);
        batches.save(b);
    }

    private JsonNode confirmedStatement(String buyer, Long supplierId, String supplierToken) throws Exception {
        Batch batch = buyAndReceive(buyer, supplierId, "RM-GLY-002", "1", "50", "13").get(0);
        setStatus(batch, BatchStatus.RELEASED);
        JsonNode s = body(call(buyer, "/api/statements", Map.of("supplierId", supplierId, "periodTo", d(0)))
                .andExpect(status().isOk()));
        call(supplierToken, "/api/portal/supplier/statements/" + s.get("id").asLong() + "/confirm", Map.of())
                .andExpect(status().isOk());
        return s;
    }

    @Test
    void concurrentInvoiceRegistrationHasOneOwnerPerSupplier() throws Exception {
        Long supplierId = supplier("SUP-INV-RACE", "invoice_race_s");
        String buyer = login("purchase1");
        String sup = login("invoice_race_s");
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ids.add(confirmedStatement(buyer, supplierId, sup).get("id").asLong());
        }

        var pool = Executors.newFixedThreadPool(ids.size());
        var ready = new CountDownLatch(ids.size());
        var start = new CountDownLatch(1);
        List<MvcResult> responses = new ArrayList<>();
        try {
            List<Future<MvcResult>> attempts = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                boolean portal = i >= 2;
                boolean multiple = i % 2 == 1;
                String path = (portal ? "/api/portal/supplier/statements/" : "/api/statements/") + ids.get(i);
                var invoice = Map.of("invoiceNo", portal ? " inv-race-01 " : "INV-RACE-01", "invoiceDate", d(0), "amount", 50);
                attempts.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("并发登记未启动");
                    return call(portal ? sup : buyer, path + (multiple ? "/invoices" : "/invoice"),
                            multiple ? Map.of("invoices", List.of(invoice)) : invoice).andReturn();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<MvcResult> attempt : attempts) {
                responses.add(attempt.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(responses).extracting(r -> r.getResponse().getStatus()).containsExactlyInAnyOrder(200, 400, 400, 400);
        var winner = responses.stream().filter(r -> r.getResponse().getStatus() == 200).findFirst().orElseThrow();
        JsonNode owner = json.readTree(winner.getResponse().getContentAsByteArray());
        long ownerId = owner.get("id").asLong();
        String ownerNo = owner.get("statementNo").asText();
        for (MvcResult failed : responses.stream().filter(r -> r.getResponse().getStatus() == 400).toList()) {
            assertThat(failed.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("已经登记过", ownerNo);
        }
        long retry = ids.stream().filter(id -> id != ownerId).findFirst().orElseThrow();
        read(buyer, "/api/statements?statementNo=" + statements.findById(retry).orElseThrow().getStatementNo())
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$[0].invoices").isEmpty());

        var sameNumber = Map.of("invoiceNo", "INV-RACE-01", "invoiceDate", d(0), "amount", 50);
        Long otherId = supplier("SUP-INV-RACE-OTHER", "invoice_race_other");
        String other = login("invoice_race_other");
        long otherStatement = confirmedStatement(buyer, otherId, other).get("id").asLong();
        call(other, "/api/portal/supplier/statements/" + otherStatement + "/invoice", sameNumber).andExpect(status().isOk());
        call(other, "/api/portal/supplier/statements/" + retry + "/invoice", sameNumber).andExpect(status().isNotFound());

        call(login("finance1"), "/api/statements/" + ownerId + "/return-invoice",
                Map.of("reason", "录错单据", "invoiceReviewVersion", 0, "password", PWD)).andExpect(status().isOk());
        call(buyer, "/api/statements/" + retry + "/invoice", sameNumber).andExpect(status().isOk());
    }

    @Test
    void invoiceLookupHandlesOlderStatementsAndLongInvoiceNumbers() throws Exception {
        Long supplierId = supplier("SUP-INV-LEGACY", "invoice_legacy_s");
        String buyer = login("purchase1");
        String sup = login("invoice_legacy_s");
        long legacyId = confirmedStatement(buyer, supplierId, sup).get("id").asLong();
        var legacy = statements.findById(legacyId).orElseThrow();
        legacy.setInvoiceNo("LEGACY-XYZ");
        legacy.setInvoiceDate(d(0));
        legacy.setInvoiceAmount(new BigDecimal("50"));
        legacy.setStatus(StatementStatus.INVOICED);
        statements.save(legacy);

        long id = confirmedStatement(buyer, supplierId, sup).get("id").asLong();
        String path = "/api/statements/" + id;
        call(buyer, path + "/invoice", Map.of("invoiceNo", "legacy-xyz", "invoiceDate", d(0), "amount", 50))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString(legacy.getStatementNo())));
        call(buyer, path + "/invoices", Map.of("invoices", List.of(
                Map.of("invoiceNo", "INVOICE-X", "invoiceDate", d(0), "amount", 25),
                Map.of("invoiceNo", " invoice-x ", "invoiceDate", d(0), "amount", 25))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("发票号重复")));

        String longNo = "1234567890".repeat(6);
        JsonNode registered = body(call(buyer, path + "/invoices", Map.of("invoices", List.of(
                Map.of("invoiceNo", longNo, "invoiceDate", d(0), "amount", 25),
                Map.of("invoiceNo", "INVOICE-SECOND", "invoiceDate", d(0), "amount", 25))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.invoices[0].invoiceNo").value(longNo)));
        long next = confirmedStatement(buyer, supplierId, sup).get("id").asLong();
        call(sup, "/api/portal/supplier/statements/" + next + "/invoice", Map.of("invoiceNo", "invoice-second", "invoiceDate", d(0), "amount", 50))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString(registered.get("statementNo").asText())));
    }

    /** 金额手算：10 × 11.3 = 113.00，税 13%：113 × 13 / 113 = 13.00；3 × 33.3333 = 100.00（99.9999 进位），税 6%：100 × 6 / 106 = 5.66。 */
    @Test
    void threeWayMatchWithHandCalculatedAmounts() throws Exception {
        Long s = supplier("SUP-SET-A", "set_a");
        supplier("SUP-SET-B", "set_b");
        String buyer = login("purchase1");
        List<Batch> b1 = buyAndReceive(buyer, s, "RM-GLY-002", "10", "11.3", "13", "RM-NIA-001", "3", "33.3333", "6");
        setStatus(b1.get(0), BatchStatus.RELEASED);
        setStatus(b1.get(1), BatchStatus.RELEASED);
        // 不结算：待检、不合格、冻结；没单价的单独列出来
        List<Batch> quarantine = buyAndReceive(buyer, s, "RM-GLY-002", "7", "100", "13");
        List<Batch> rejected = buyAndReceive(buyer, s, "RM-GLY-002", "8", "100", "13");
        setStatus(rejected.get(0), BatchStatus.REJECTED);
        List<Batch> frozen = buyAndReceive(buyer, s, "RM-GLY-002", "9", "100", "13");
        setStatus(frozen.get(0), BatchStatus.FROZEN);
        List<Batch> noPrice = buyAndReceive(buyer, s, "RM-GLY-002", "5", "", "");
        setStatus(noPrice.get(0), BatchStatus.RELEASED);

        JsonNode pv = body(read(buyer, "/api/statements/preview?supplierId=" + s + "&periodTo=" + d(0)).andExpect(status().isOk()));
        assertThat(pv.get("lines").size()).isEqualTo(2);
        assertThat(pv.get("goodsAmount").decimalValue()).isEqualByComparingTo("213.00");
        assertThat(pv.get("taxAmount").decimalValue()).isEqualByComparingTo("18.66");
        assertThat(pv.get("missingPrice").size()).isEqualTo(1);
        assertThat(pv.get("missingPrice").get(0).get("batchNo").asText()).isEqualTo(noPrice.get(0).getBatchNo());
        // 截止日期之前入库的才算
        assertThat(body(read(buyer, "/api/statements/preview?supplierId=" + s + "&periodTo=" + d(-1))).get("lines").size()).isEqualTo(0);

        // 调整要有原因，合计 = 213 - 13 = 200
        call(buyer, "/api/statements", Map.of("supplierId", s, "periodTo", d(0).toString(), "adjustment", -13)).andExpect(status().isBadRequest());
        JsonNode st = body(call(buyer, "/api/statements", Map.of("supplierId", s, "periodTo", d(0).toString(), "creditDays", 0,
                "adjustment", -13, "adjustmentNote", "包装破损扣款")).andExpect(status().isOk()));
        long id = st.get("id").asLong();
        assertThat(st.get("totalAmount").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(st.get("status").asText()).isEqualTo("SENT");
        // 同一批次不能再结算
        call(buyer, "/api/statements", Map.of("supplierId", s, "periodTo", d(0).toString())).andExpect(status().isBadRequest());
        assertThat(body(read(buyer, "/api/statements/preview?supplierId=" + s + "&periodTo=" + d(0))).get("lines").size()).isEqualTo(0);
        // 进了对账单的单价不能改
        long poId = poByNo(buyer, b1.get(0).getSourceNo()).get("id").asLong();
        long poLine = poByNo(buyer, b1.get(0).getSourceNo()).get("lines").get(0).get("id").asLong();
        call(buyer, "/api/purchase-orders/" + poId + "/prices", Map.of("reason", "改价", "lines", List.of(Map.of("poLineId", poLine, "unitPrice", 1, "taxRate", 13)))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("已进了对账单")));

        // 别家看不到也动不了
        String other = login("set_b");
        assertThat(body(read(other, "/api/portal/supplier/statements")).size()).isEqualTo(0);
        call(other, "/api/portal/supplier/statements/" + id + "/confirm", Map.of()).andExpect(status().isNotFound());
        read(other, "/api/portal/supplier/statements/" + id + "/attachments").andExpect(status().isNotFound());

        String sup = login("set_a");
        assertThat(body(read(sup, "/api/portal/supplier/statements")).get(0).get("adjustmentNote").asText()).isEqualTo("包装破损扣款");
        Map<String, Object> inv = new HashMap<>(Map.of("invoiceNo", "INV-1", "invoiceDate", d(0).toString(), "amount", new BigDecimal("200.00")));
        // 没确认不能开票
        call(sup, "/api/portal/supplier/statements/" + id + "/invoice", inv).andExpect(status().isBadRequest());
        call(sup, "/api/portal/supplier/statements/" + id + "/confirm", Map.of()).andExpect(status().isOk());
        call(sup, "/api/portal/supplier/statements/" + id + "/confirm", Map.of()).andExpect(status().isBadRequest());
        // 发票对不上不行
        var wrong = new HashMap<>(inv);
        wrong.put("amount", new BigDecimal("200.01"));
        call(sup, "/api/portal/supplier/statements/" + id + "/invoice", wrong).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不一致")));
        var future = new HashMap<>(inv);
        future.put("invoiceDate", d(1).toString());
        call(sup, "/api/portal/supplier/statements/" + id + "/invoice", future).andExpect(status().isBadRequest());
        call(sup, "/api/portal/supplier/statements/" + id + "/invoice", inv).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INVOICED"));

        // 采购不能审批；审批人不能是制单人
        call(buyer, "/api/statements/" + id + "/approve", Map.of("password", PWD)).andExpect(status().isForbidden());
        String fin = login("finance1");
        call(fin, "/api/statements/" + id + "/pay", Map.of("payDate", d(0).toString(), "amount", 1)).andExpect(status().isBadRequest());
        call(fin, "/api/statements/" + id + "/approve", Map.of("password", "wrong")).andExpect(status().isBadRequest());
        assertThat(text(read(fin, "/api/dashboard"))).contains("对账单待审批");
        call(fin, "/api/statements/" + id + "/approve", Map.of("password", PWD)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        String no = st.get("statementNo").asText();
        var dueTodo = "$.todos[?(@.ref == '%s')].label".formatted(no);
        read(fin, "/api/dashboard").andExpect(status().isOk())
                .andExpect(jsonPath(dueTodo).value(org.hamcrest.Matchers.contains("对账单付款今日到期")));
        var inQueue = "$[?(@.statementNo == '%s')]".formatted(no);
        read(fin, "/api/statements?followUp=PAY").andExpect(status().isOk()).andExpect(jsonPath(inQueue).isNotEmpty());
        read(fin, "/api/statements?followUp=OVERDUE").andExpect(status().isOk()).andExpect(jsonPath(inQueue).isEmpty());
        // 审批后不能作废
        call(buyer, "/api/statements/" + id + "/cancel", Map.of("reason", "x")).andExpect(status().isBadRequest());

        // 分批付款，不能超付
        call(fin, "/api/statements/" + id + "/pay", Map.of("payDate", d(0).toString(), "amount", new BigDecimal("150"), "referenceNo", "BANK-1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.unpaid").value(50.00));
        call(fin, "/api/statements/" + id + "/pay", Map.of("payDate", d(0).toString(), "amount", new BigDecimal("50.01"))).andExpect(status().isBadRequest());
        call(fin, "/api/statements/" + id + "/pay", Map.of("payDate", d(0).toString(), "amount", new BigDecimal("50"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID")).andExpect(jsonPath("$.payments.length()").value(2));
        read(fin, "/api/statements?followUp=PAY").andExpect(status().isOk()).andExpect(jsonPath(inQueue).isEmpty());
        // 导出
        read(fin, "/api/reports/statements").andExpect(status().isOk());
        assertThat(quarantine).hasSize(1);
    }

    @Test
    void returnedInvoicesRetainHistoryAndRequireReviewOfTheCurrentSubmission() throws Exception {
        Long supplierId = supplier("SUP-INVOICE-RETURN", "invoice_return_s");
        supplier("SUP-INVOICE-OTHER", "invoice_other_s");
        String buyer = login("purchase1");
        String finance = login("finance1");
        String sup = login("invoice_return_s");
        Batch batch = buyAndReceive(buyer, supplierId, "RM-GLY-002", "3", "40", "13").get(0);
        setStatus(batch, BatchStatus.RELEASED);
        JsonNode st = body(call(buyer, "/api/statements", Map.of("supplierId", supplierId, "periodTo", d(0)))
                .andExpect(status().isOk()));
        long id = st.get("id").asLong();
        String no = st.get("statementNo").asText();
        String internal = "/api/statements/" + id;
        String portal = "/api/portal/supplier/statements/" + id;
        call(sup, portal + "/confirm", Map.of()).andExpect(status().isOk());
        var first = new HashMap<String, Object>(Map.of("invoiceNo", "INV-RETURN-A", "invoiceType", "BLUE", "invoiceDate", d(0), "taxRate", 9, "amount", 80));
        var second = Map.of("invoiceNo", "INV-RETURN-B", "invoiceType", "BLUE", "invoiceDate", d(0), "taxRate", 6, "amount", 40);
        call(sup, portal + "/invoices", Map.of("invoices", List.of(first, second))).andExpect(status().isOk());

        String reason = "请核对第一张税率，错误样例金额 804947.42";
        var sendBack = Map.of("reason", reason, "password", PWD, "invoiceReviewVersion", 0);
        call(buyer, internal + "/return-invoice", sendBack).andExpect(status().isForbidden());
        call(finance, internal + "/return-invoice", Map.of("reason", reason, "password", "wrong", "invoiceReviewVersion", 0))
                .andExpect(status().isBadRequest());
        call(finance, internal + "/return-invoice", sendBack).andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceCorrectionPending").value(true))
                .andExpect(jsonPath("$.invoiceNo").isEmpty()).andExpect(jsonPath("$.invoices").isEmpty())
                .andExpect(jsonPath("$.invoiceReturns[0].reason").value(reason))
                .andExpect(jsonPath("$.invoiceReturns[0].returnedBy").value("finance1"))
                .andExpect(jsonPath("$.invoiceReturns[0].invoices.length()").value(2))
                .andExpect(jsonPath("$.lines[0].batchNo").value(batch.getBatchNo()))
                .andExpect(jsonPath("$.totalAmount").value(120.0));
        call(finance, internal + "/return-invoice", sendBack).andExpect(status().isBadRequest());
        call(finance, internal + "/approve", Map.of("password", PWD, "invoiceReviewVersion", 1)).andExpect(status().isBadRequest());

        String viewer = login("viewer1");
        assertThat(text(read(viewer, "/api/dashboard"))).contains(no, "对账发票待更正").doesNotContain("804947.42");
        assertThat(text(read(viewer, "/api/audit?limit=300"))).doesNotContain("804947.42");
        assertThat(text(read(login("invoice_other_s"), "/api/portal/supplier/statements"))).doesNotContain(no, reason);
        read(sup, "/api/portal/supplier/statements").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].invoiceReturns[0].reason").value(reason));
        assertThat(body(read(buyer, "/api/statements/preview?supplierId=" + supplierId + "&periodTo=" + d(0)))
                .get("lines").isEmpty()).isTrue();

        first.put("taxRate", 13);
        // 采购在原单上代更正，同一发票号可以重提。
        call(buyer, internal + "/invoices", Map.of("invoices", List.of(first, second))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVOICED"))
                .andExpect(jsonPath("$.invoices[0].taxRate").value(13.0))
                .andExpect(jsonPath("$.invoiceReturns[0].invoices[0].taxRate").value(9.0));
        call(finance, internal + "/return-invoice", sendBack).andExpect(status().isBadRequest());
        call(finance, internal + "/approve", Map.of("password", PWD, "invoiceReviewVersion", 0))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("发票已更正")));
        call(finance, internal + "/approve", Map.of("password", PWD)).andExpect(status().isBadRequest());
        call(finance, internal + "/approve", Map.of("password", PWD, "invoiceReviewVersion", 1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.invoiceReturns.length()").value(1));
    }

    @Test
    void disputeThenVoidReleasesBatchesAndApproverCannotBeTheMaker() throws Exception {
        Long s = supplier("SUP-SET-C", "set_c");
        String admin = login("admin", "Admin@123456");
        List<Batch> b = buyAndReceive(admin, s, "RM-GLY-002", "2", "50", "0");
        setStatus(b.get(0), BatchStatus.RELEASED);
        JsonNode st = body(call(admin, "/api/statements", Map.of("supplierId", s, "periodTo", d(0).toString())).andExpect(status().isOk()));
        long id = st.get("id").asLong();
        String sup = login("set_c");
        call(sup, "/api/portal/supplier/statements/" + id + "/dispute", Map.of()).andExpect(status().isBadRequest());
        call(sup, "/api/portal/supplier/statements/" + id + "/dispute", Map.of("note", "数量不对")).andExpect(status().isOk());
        assertThat(text(read(login("purchase1"), "/api/dashboard"))).contains("对账单有异议").contains("数量不对");
        // 有异议不能确认；作废后批次放出来，可以重新生成
        call(sup, "/api/portal/supplier/statements/" + id + "/confirm", Map.of()).andExpect(status().isBadRequest());
        call(admin, "/api/statements/" + id + "/cancel", Map.of("reason", "重新核对")).andExpect(status().isOk());
        JsonNode again = body(call(admin, "/api/statements", Map.of("supplierId", s, "periodTo", d(0).toString())).andExpect(status().isOk()));
        long id2 = again.get("id").asLong();
        assertThat(again.get("totalAmount").decimalValue()).isEqualByComparingTo("100");
        // 作废的发票号可以再用：这里走到审批
        call(sup, "/api/portal/supplier/statements/" + id2 + "/confirm", Map.of()).andExpect(status().isOk());
        call(admin, "/api/statements/" + id2 + "/invoice", Map.of("invoiceNo", "INV-C", "invoiceDate", d(0).toString(), "amount", 100)).andExpect(status().isOk());
        call(admin, "/api/statements/" + id2 + "/approve", Map.of("password", "Admin@123456")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("不能是制单人")));
        call(login("finance1"), "/api/statements/" + id2 + "/approve", Map.of("password", PWD)).andExpect(status().isOk());
    }

    @Test
    void changingAnExistingPriceNeedsAReason() throws Exception {
        Long s = supplier("SUP-SET-D", "set_d");
        String buyer = login("purchase1");
        JsonNode po = body(call(buyer, "/api/purchase-orders", Map.of("supplierId", s, "lines", List.of(Map.of("itemId", item("RM-GLY-002"), "qty", 1)))).andExpect(status().isOk()));
        long id = po.get("id").asLong();
        long line = po.get("lines").get(0).get("id").asLong();
        call(buyer, "/api/purchase-orders/" + id + "/prices", Map.of("lines", List.of(Map.of("poLineId", line, "unitPrice", 9, "taxRate", 13)))).andExpect(status().isOk());
        call(buyer, "/api/purchase-orders/" + id + "/prices", Map.of("lines", List.of(Map.of("poLineId", line, "unitPrice", 8, "taxRate", 13)))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("写明原因")));
        call(buyer, "/api/purchase-orders/" + id + "/prices", Map.of("reason", "谈判降价", "lines", List.of(Map.of("poLineId", line, "unitPrice", 8, "taxRate", 13)))).andExpect(status().isOk());
        read(login("qa1"), "/api/purchase-orders").andExpect(status().isOk());
        call(login("qa1"), "/api/purchase-orders/" + id + "/prices", Map.of("lines", List.of(Map.of("poLineId", line, "unitPrice", 1, "taxRate", 13)))).andExpect(status().isForbidden());
    }
}
