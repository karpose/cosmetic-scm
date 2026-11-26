package com.cosmetic.scm;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import com.cosmetic.scm.demo.DemoDataLoader;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** V56 对账结算资金汇总接口与权限隔离集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class V56QmsSettlementAuditVerifyTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String login(String u) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", u, "password", PWD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void settlementSummaryEnforcesPriceAndArPermissions() throws Exception {
        String finance = login("finance1");
        String purchase = login("purchase1");
        String qa = login("qa1");

        mvc.perform(get("/api/statements/summary").header("Authorization", "Bearer " + finance))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apPendingApproveCount").isNumber())
                .andExpect(jsonPath("$.apOpenAmount").isNumber())
                .andExpect(jsonPath("$.arOpenCount").isNumber())
                .andExpect(jsonPath("$.arOpenAmount").isNumber());

        mvc.perform(get("/api/statements/summary").header("Authorization", "Bearer " + purchase))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apOpenAmount").isNumber())
                .andExpect(jsonPath("$.arOpenAmount").doesNotExist());

        mvc.perform(get("/api/statements/summary").header("Authorization", "Bearer " + qa))
                .andExpect(status().isForbidden());
    }

    /** 销售能进对账结算看客户应收，但应付的汇总和账龄不给；采购反过来看不到应收账龄。 */
    @Test
    void salesSeesReceivablesButNotPayables() throws Exception {
        String sales = login("sales1");
        String purchase = login("purchase1");

        mvc.perform(get("/api/statements/summary").header("Authorization", "Bearer " + sales))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.arOpenCount").isNumber())
                .andExpect(jsonPath("$.arOpenAmount").isNumber())
                .andExpect(jsonPath("$.apOpenAmount").doesNotExist())
                .andExpect(jsonPath("$.apOverdueAmount").doesNotExist())
                .andExpect(jsonPath("$.apPendingApproveCount").doesNotExist());
        mvc.perform(get("/api/statements").header("Authorization", "Bearer " + sales))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/statements/invoice-ledger").header("Authorization", "Bearer " + sales))
                .andExpect(status().isForbidden());

        // 账龄：默认是应付，销售取不到；应收可以
        mvc.perform(get("/api/statements/aging").header("Authorization", "Bearer " + sales))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/statements/aging").param("side", "AP").header("Authorization", "Bearer " + sales))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/statements/aging").param("side", "AR").header("Authorization", "Bearer " + sales))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.side").value("AR"));
        mvc.perform(get("/api/statements/aging").param("side", "AR").header("Authorization", "Bearer " + purchase))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/statements/aging").param("side", "AP").header("Authorization", "Bearer " + purchase))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.side").value("AP"));

        // 客户应收的几个接口照旧给销售
        for (String url : new String[] {"/api/ar-statements", "/api/ar-statements/credit-summary", "/api/ar-statements/collection-board"}) {
            mvc.perform(get(url).header("Authorization", "Bearer " + sales)).andExpect(status().isOk());
        }
    }
}
