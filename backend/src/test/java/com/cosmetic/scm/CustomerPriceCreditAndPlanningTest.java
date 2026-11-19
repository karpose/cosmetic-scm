package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.ItemRepository;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.PartnerRepository;
import com.cosmetic.scm.master.WarehouseRepository;
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

/** V43 客户协议价自动带价、客户信用额度风控与补货协议价联动集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-customer-credit;MODE=MySQL;DB_CLOSE_DELAY=-1")
class CustomerPriceCreditAndPlanningTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PartnerRepository partners;
    @Autowired ItemRepository items;
    @Autowired WarehouseRepository warehouses;

    private String login(String u) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", u, "password", PWD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void customerPriceAutoFillsAndCreditLimitBlocksExcessShipment() throws Exception {
        Partner customer = partners.findByCode("CUS-CR-43").orElseGet(() -> {
            Partner p = new Partner();
            p.setCode("CUS-CR-43");
            p.setName("授信风控测试客户");
            p.setKind(PartnerKind.CUSTOMER);
            p.setMinShelfPct(0);
            p.setCreditLimit(new BigDecimal("500.00"));
            p.setDefaultTaxRate(new BigDecimal("13.00"));
            return partners.save(p);
        });
        Item fg = items.findAll().stream().filter(i -> i.getItemType() == ItemType.FG).findFirst().orElseThrow();
        long whId = warehouses.findAll().stream().filter(w -> "WH-MAIN".equals(w.getCode())).findFirst().orElseThrow().getId();

        String sales = login("sales1");
        String qa = login("qa1");

        mvc.perform(get("/api/customer-prices").header("Authorization", "Bearer " + qa))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/customer-prices").header("Authorization", "Bearer " + sales)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "customerId", customer.getId(),
                                "itemId", fg.getId(),
                                "unitPrice", new BigDecimal("200.00"),
                                "note", "V43 测试协议价"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveToday").value(true));

        mvc.perform(get("/api/reports/customer-prices").header("Authorization", "Bearer " + sales))
                .andExpect(status().isOk());
        mvc.perform(get("/api/reports/customer-prices").header("Authorization", "Bearer " + qa))
                .andExpect(status().isForbidden());

        // 2 件 × ¥200 = ¥400 <= ¥500 额度，自动带入协议价并成功出库
        String okBody = mvc.perform(post("/api/sales-orders").header("Authorization", "Bearer " + sales)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "customerId", customer.getId(),
                                "warehouseId", whId,
                                "lines", List.of(Map.of("itemId", fg.getId(), "qty", 2))))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode so = json.readTree(okBody);
        assertThat(so.get("lines").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("200.00");

        // 3 件 × ¥200 = ¥600 > ¥500 额度，被信用风控拦截
        mvc.perform(post("/api/sales-orders").header("Authorization", "Bearer " + sales)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "customerId", customer.getId(),
                                "warehouseId", whId,
                                "lines", List.of(Map.of("itemId", fg.getId(), "qty", 3))))))
                .andExpect(status().isBadRequest());
    }
}
