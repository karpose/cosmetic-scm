package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.master.ItemRepository;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.PartnerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 供应商协议价 / 价目表：权限隔离、生效区间、下采购单自动带价与偏离审计留痕。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SupplierPriceTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PartnerRepository partners;
    @Autowired ItemRepository items;
    @Autowired TimeProvider time;

    private LocalDate d(int n) {
        return time.today().plusDays(n);
    }

    private String login(String u) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", u, "password", PWD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    private ResultActions call(String token, String url, Object body) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions read(String token, String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", "Bearer " + token));
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private Long item(String code) {
        return items.findAll().stream().filter(i -> code.equals(i.getCode())).findFirst().orElseThrow().getId();
    }

    @Test
    void contractPriceAutoFillsPurchaseOrderAndAuditsDeviations() throws Exception {
        Partner p = partners.findByCode("SUP-PRICE-40").orElseGet(() -> {
            Partner n = new Partner();
            n.setCode("SUP-PRICE-40");
            n.setName("协议价测试供应商");
            n.setKind(PartnerKind.SUPPLIER);
            n.setQualified(true);
            return partners.save(n);
        });
        Long itemId = item("RM-GLY-002");
        String buyer = login("purchase1");
        String finance = login("finance1");

        // 非采购/财务/管理员不可访问协议价
        read(login("qa1"), "/api/supplier-prices").andExpect(status().isForbidden());
        read(login("viewer1"), "/api/supplier-prices").andExpect(status().isForbidden());
        call(finance, "/api/supplier-prices", Map.of(
                "supplierId", p.getId(), "itemId", itemId, "unitPrice", new BigDecimal("18.50"),
                "taxRate", new BigDecimal("9.00"), "validFrom", d(-5).toString())).andExpect(status().isForbidden());

        // 采购新增有效协议价
        JsonNode created = body(call(buyer, "/api/supplier-prices", Map.of(
                "supplierId", p.getId(),
                "itemId", itemId,
                "unitPrice", new BigDecimal("18.50"),
                "taxRate", new BigDecimal("9.00"),
                "validFrom", d(-5).toString(),
                "validTo", d(30).toString(),
                "note", "2026 年度框架协议"
        )).andExpect(status().isOk()));
        long priceId = created.get("id").asLong();
        assertThat(created.get("effectiveToday").asBoolean()).isTrue();

        // 查询生效协议价
        read(finance, "/api/supplier-prices/effective?supplierId=" + p.getId() + "&itemId=" + itemId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unitPrice").value(18.5))
                .andExpect(jsonPath("$.taxRate").value(9.0));

        // 1. 下采购单不填单价时，自动带入协议价 18.50 与税率 9%
        JsonNode autoPo = body(call(buyer, "/api/purchase-orders", Map.of(
                "supplierId", p.getId(),
                "lines", List.of(Map.of("itemId", itemId, "qty", 10))
        )).andExpect(status().isOk()));
        assertThat(autoPo.get("lines").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("18.50");
        assertThat(autoPo.get("lines").get(0).get("taxRate").decimalValue()).isEqualByComparingTo("9.00");

        // 2. 下采购单填写偏离协议价的单价（21.00），写入金额审计日志
        JsonNode devPo = body(call(buyer, "/api/purchase-orders", Map.of(
                "supplierId", p.getId(),
                "lines", List.of(Map.of("itemId", itemId, "qty", 5, "unitPrice", new BigDecimal("21.00"), "taxRate", new BigDecimal("9.00")))
        )).andExpect(status().isOk()));
        String devPoNo = devPo.get("poNo").asText();
        String auditText = read(finance, "/api/audit?q=" + devPoNo).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(auditText).contains("采购单偏离协议价").contains("18.5").contains("21");

        // 3. 停用协议价后不再自动带价
        call(buyer, "/api/supplier-prices/" + priceId + "/deactivate", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        JsonNode afterDeactivatePo = body(call(buyer, "/api/purchase-orders", Map.of(
                "supplierId", p.getId(),
                "lines", List.of(Map.of("itemId", itemId, "qty", 3))
        )).andExpect(status().isOk()));
        assertThat(afterDeactivatePo.get("lines").get(0).get("unitPrice").isNull()).isTrue();

        // 4. 即将到期协议价进入采购/财务总览待办（非价格角色不可见），且支持 Excel 导出
        call(buyer, "/api/supplier-prices", Map.of(
                "supplierId", p.getId(), "itemId", itemId, "unitPrice", new BigDecimal("19.00"),
                "taxRate", new BigDecimal("13.00"), "validFrom", d(-10).toString(), "validTo", d(5).toString(), "note", "快到期协议"
        )).andExpect(status().isOk());
        String buyerDash = read(buyer, "/api/dashboard").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(buyerDash).contains("PRICE_EXPIRING").contains("SUP-PRICE-40");
        String viewerDash = read(login("viewer1"), "/api/dashboard").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(viewerDash).doesNotContain("PRICE_EXPIRING");

        read(finance, "/api/reports/supplier-prices").andExpect(status().isOk());
        read(login("viewer1"), "/api/reports/supplier-prices").andExpect(status().isForbidden());
    }
}
