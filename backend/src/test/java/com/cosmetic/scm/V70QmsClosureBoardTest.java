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
import com.cosmetic.scm.domain.CapaSource;
import com.cosmetic.scm.domain.CapaType;
import com.cosmetic.scm.domain.DeviationArea;
import com.cosmetic.scm.domain.DeviationLevel;
import com.cosmetic.scm.qms.DeviationService;
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

/** V70 偏差与 CAPA 超期闭环升级监控看板及报表导出集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-v70;MODE=MySQL;DB_CLOSE_DELAY=-1")
class V70QmsClosureBoardTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DeviationService deviationService;

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
    void closureBoardClassifiesOverdueBlockingVerifyPendingAndExportsReport() throws Exception {
        LocalDate today = LocalDate.now();
        // 1) 重大偏差已超期 2 天 -> OVERDUE 且计入 blockingCount
        var overdueMajor = as("prod1", () -> deviationService.create(new DeviationService.DeviationReq(
                today.minusDays(5), DeviationArea.PRODUCTION, DeviationLevel.MAJOR, null,
                "V70 乳化温度超限", "均质锅温度高于工艺上限 3℃", "立即暂停灌装隔离", today.minusDays(2))));

        // 2) 关联 CAPA 已完成待 QA 验证 -> VERIFY_PENDING
        var capa = as("qa1", () -> deviationService.createCapa(new DeviationService.CapaReq(
                CapaSource.DEVIATION, null, overdueMajor.id(), CapaType.CORRECTIVE,
                "校准温控传感器并复核 PID 参数", "prod1", today.plusDays(10))));
        as("prod1", () -> deviationService.finishCapa(capa.id(), new DeviationService.CapaDoneReq("已完成传感器更换与复校")));

        // 3) 微小偏差 2 天后到期 -> DUE_SOON
        var dueSoonMinor = as("prod1", () -> deviationService.create(new DeviationService.DeviationReq(
                today.minusDays(1), DeviationArea.WAREHOUSE, DeviationLevel.MINOR, null,
                "V70 外箱标签轻微歪斜", "抽检发现 2 箱外箱贴标偏斜", "现场重新贴标", today.plusDays(2))));

        String qaToken = login("qa1");
        JsonNode board = json.readTree(mvc.perform(get("/api/qms/closure-board")
                        .header("Authorization", "Bearer " + qaToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(board.get("overdueCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(board.get("blockingCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(board.get("verifyPendingCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(board.get("dueSoonCount").asInt()).isGreaterThanOrEqualTo(1);

        JsonNode overRow = findByRefNo(board.get("rows"), overdueMajor.deviationNo());
        JsonNode capaRow = findByRefNo(board.get("rows"), capa.capaNo());
        JsonNode soonRow = findByRefNo(board.get("rows"), dueSoonMinor.deviationNo());
        assertThat(overRow).isNotNull();
        assertThat(overRow.get("riskLevel").asText()).isEqualTo("OVERDUE");
        assertThat(overRow.get("daysLeft").asLong()).isEqualTo(-2L);

        assertThat(capaRow).isNotNull();
        assertThat(capaRow.get("riskLevel").asText()).isEqualTo("VERIFY_PENDING");

        assertThat(soonRow).isNotNull();
        assertThat(soonRow.get("riskLevel").asText()).isEqualTo("DUE_SOON");
        assertThat(soonRow.get("daysLeft").asLong()).isEqualTo(2L);

        byte[] xlsx = mvc.perform(get("/api/reports/qms-closure").header("Authorization", "Bearer " + qaToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(xlsx.length).isGreaterThan(100);
    }

    private static JsonNode findByRefNo(JsonNode arr, String refNo) {
        for (JsonNode n : arr) {
            if (refNo.equals(n.get("refNo").asText())) {
                return n;
            }
        }
        return null;
    }
}
