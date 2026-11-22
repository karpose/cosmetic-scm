package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.ChangeStatus;
import com.cosmetic.scm.domain.ChangeType;
import com.cosmetic.scm.domain.RiskLevel;
import com.cosmetic.scm.plm.ChangeAction;
import com.cosmetic.scm.plm.ChangeActionRepository;
import com.cosmetic.scm.plm.ChangeRequest;
import com.cosmetic.scm.plm.ChangeRequestRepository;
import com.cosmetic.scm.plm.ChangeService;
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

/** V72 变更控制与行动超期闭环监控看板及报表导出集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-v72;MODE=MySQL;DB_CLOSE_DELAY=-1")
class V72ChangeClosureBoardTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ChangeService changeService;
    @Autowired ChangeRequestRepository changeRepo;
    @Autowired ChangeActionRepository actionRepo;
    @Autowired TimeProvider time;

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
    void closureBoardClassifiesActionOverdueReadyToCloseAndPendingAssessAndExportsReport() throws Exception {
        LocalDate today = time.today();
        LocalDateTime now = time.now();
        // 1) 实施中且含超期行动项的变更 -> ACTION_OVERDUE
        ChangeRequest overdueChange = new ChangeRequest();
        overdueChange.setChangeNo("CC-V72-OVER");
        overdueChange.setTitle("V72 乳化工艺参数变更");
        overdueChange.setChangeType(ChangeType.PROCESS);
        overdueChange.setReason("提升批次稳定性");
        overdueChange.setDescription("调整均质时间与冷却曲线");
        overdueChange.setStatus(ChangeStatus.APPROVED);
        overdueChange.setRisk(RiskLevel.HIGH);
        overdueChange.setImpact("需验证连续三批工艺一致性");
        overdueChange.setCreatedBy("prod1");
        overdueChange.setCreatedAt(now.minusDays(15));
        overdueChange.setAssessedBy("qa1");
        overdueChange.setApprovedBy("qso1");
        overdueChange = changeRepo.save(overdueChange);

        ChangeAction overdueAct = new ChangeAction();
        overdueAct.setChangeId(overdueChange.getId());
        overdueAct.setLineNo(1);
        overdueAct.setContent("完成工艺验证报告");
        overdueAct.setOwner("qa1");
        overdueAct.setDueDate(today.minusDays(3));
        overdueAct.setDone(false);
        actionRepo.save(overdueAct);

        // 2) 实施中且行动已全部完成的变更 -> READY_TO_CLOSE
        ChangeRequest readyChange = new ChangeRequest();
        readyChange.setChangeNo("CC-V72-READY");
        readyChange.setTitle("V72 外盒标签排版微调");
        readyChange.setChangeType(ChangeType.LABEL);
        readyChange.setReason("更新客服热线");
        readyChange.setDescription("仅变更外盒背面客服电话");
        readyChange.setStatus(ChangeStatus.APPROVED);
        readyChange.setRisk(RiskLevel.LOW);
        readyChange.setImpact("不影响配方与合规成分表");
        readyChange.setCreatedBy("prod1");
        readyChange.setCreatedAt(now.minusDays(5));
        readyChange.setAssessedBy("qa1");
        readyChange.setApprovedBy("qso1");
        readyChange = changeRepo.save(readyChange);

        ChangeAction doneAct = new ChangeAction();
        doneAct.setChangeId(readyChange.getId());
        doneAct.setLineNo(1);
        doneAct.setContent("确认新版设计稿印刷样张");
        doneAct.setOwner("qa1");
        doneAct.setDueDate(today.plusDays(5));
        doneAct.setDone(true);
        doneAct.setDoneBy("qa1");
        doneAct.setDoneAt(now);
        doneAct.setDoneNote("已签样确认");
        actionRepo.save(doneAct);

        // 3) 待评估变更 -> PENDING_ASSESS
        var submitted = as("prod1", () -> changeService.create(new ChangeService.CreateReq(
                "V72 瓶器供应商切换", ChangeType.PACKAGE, "新增备选供应商", "增加 50ml 真空瓶备选包材商", "面霜系列")));

        String qaToken = login("qa1");
        JsonNode board = json.readTree(mvc.perform(get("/api/changes/closure-board")
                        .header("Authorization", "Bearer " + qaToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(board.get("actionOverdueCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(board.get("readyToCloseCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(board.get("pendingAssessCount").asInt()).isGreaterThanOrEqualTo(1);

        JsonNode overRow = findByChangeNo(board.get("rows"), "CC-V72-OVER");
        JsonNode readyRow = findByChangeNo(board.get("rows"), "CC-V72-READY");
        JsonNode subRow = findByChangeNo(board.get("rows"), submitted.change().changeNo());
        assertThat(overRow).isNotNull();
        assertThat(overRow.get("closureState").asText()).isEqualTo("ACTION_OVERDUE");
        assertThat(overRow.get("actionsOverdue").asInt()).isEqualTo(1);

        assertThat(readyRow).isNotNull();
        assertThat(readyRow.get("closureState").asText()).isEqualTo("READY_TO_CLOSE");

        assertThat(subRow).isNotNull();
        assertThat(subRow.get("closureState").asText()).isEqualTo("PENDING_ASSESS");

        byte[] xlsx = mvc.perform(get("/api/reports/change-closure").header("Authorization", "Bearer " + qaToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(xlsx.length).isGreaterThan(100);
    }

    private static JsonNode findByChangeNo(JsonNode arr, String changeNo) {
        for (JsonNode n : arr) {
            if (changeNo.equals(n.get("changeNo").asText())) {
                return n;
            }
        }
        return null;
    }
}
