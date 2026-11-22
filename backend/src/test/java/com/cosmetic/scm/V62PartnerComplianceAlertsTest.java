package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.master.MasterDtos;
import com.cosmetic.scm.master.MasterService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** V62 供应商与受托厂证照到期及审核合规预警分级与导出集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-v62;MODE=MySQL;DB_CLOSE_DELAY=-1")
class V62PartnerComplianceAlertsTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MasterService master;

    private <T> T as(String user, Supplier<T> s) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        try {
            return s.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private String login(String u) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", u, "password", PWD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void auditPartnersClassifiesLicenseAndAuditComplianceRisksAndExportsReport() throws Exception {
        LocalDate today = LocalDate.now();
        as("admin", () -> {
            master.createPartner(new MasterDtos.PartnerReq(
                    "SUP-V62-EXP", "V62 证照过期厂", PartnerKind.SUPPLIER, "LIC-EXP", today.minusDays(5),
                    null, null, null, 0, null, null, true, false));
            master.createPartner(new MasterDtos.PartnerReq(
                    "SUP-V62-WARN", "V62 证照临期厂", PartnerKind.SUPPLIER, "LIC-WARN", today.plusDays(30),
                    null, null, null, 0, null, null, true, false));
            return null;
        });

        String qaToken = login("qa1");
        JsonNode list = json.readTree(mvc.perform(get("/api/qms/audit-partners")
                        .header("Authorization", "Bearer " + qaToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        JsonNode expRow = findByCode(list, "SUP-V62-EXP");
        assertThat(expRow).isNotNull();
        assertThat(expRow.get("licenseState").asText()).isEqualTo("EXPIRED");
        assertThat(expRow.get("complianceRisk").asText()).isEqualTo("HIGH");

        JsonNode warnRow = findByCode(list, "SUP-V62-WARN");
        assertThat(warnRow).isNotNull();
        assertThat(warnRow.get("licenseState").asText()).isEqualTo("EXPIRING");
        assertThat(warnRow.get("complianceRisk").asText()).isEqualTo("MEDIUM");

        byte[] xlsx = mvc.perform(get("/api/reports/partner-compliance").header("Authorization", "Bearer " + qaToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(xlsx.length).isGreaterThan(100);
    }

    private static JsonNode findByCode(JsonNode arr, String code) {
        for (JsonNode n : arr) {
            if (code.equals(n.get("code").asText())) {
                return n;
            }
        }
        return null;
    }
}
