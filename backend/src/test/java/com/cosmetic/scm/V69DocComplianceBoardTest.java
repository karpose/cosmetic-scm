package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.DocCategory;
import com.cosmetic.scm.domain.DocStatus;
import com.cosmetic.scm.domain.RevisionStatus;
import com.cosmetic.scm.domain.Role;
import com.cosmetic.scm.plm.ControlledDoc;
import com.cosmetic.scm.plm.ControlledDocRepository;
import com.cosmetic.scm.plm.DocControlService;
import com.cosmetic.scm.plm.DocRevision;
import com.cosmetic.scm.plm.DocRevisionRepository;
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

/** V69 受控文件复审、培训签收与纸质副本回收合规闭环看板及导出集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-v69;MODE=MySQL;DB_CLOSE_DELAY=-1")
class V69DocComplianceBoardTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DocControlService docControl;
    @Autowired ControlledDocRepository docRepo;
    @Autowired DocRevisionRepository revRepo;

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
    void complianceBoardSurfacesOverdueReviewAckAndPendingApprovalAndExportsReport() throws Exception {
        LocalDate today = LocalDate.now();
        // 1) 造一份复审超期且生效超 14 天仍有 QA 未签收的在用受控文件
        ControlledDoc overdueDoc = new ControlledDoc();
        overdueDoc.setDocCode("SOP-V69-01");
        overdueDoc.setTitle("V69 灌装清洁消毒规程");
        overdueDoc.setCategory(DocCategory.SOP);
        overdueDoc.setOwnerDept("质量部");
        overdueDoc.setReviewMonths(12);
        overdueDoc.setStatus(DocStatus.ACTIVE);
        overdueDoc.setLastReviewedOn(today.minusMonths(13));
        overdueDoc.setTrainRoles("QA");
        overdueDoc.setCreatedBy("qa1");
        overdueDoc.setCreatedAt(LocalDateTime.now().minusMonths(13));
        overdueDoc = docRepo.save(overdueDoc);

        DocRevision rev = new DocRevision();
        rev.setDocId(overdueDoc.getId());
        rev.setRevNo(1);
        rev.setChangeSummary("首版发布");
        rev.setEffectiveDate(today.minusDays(20));
        rev.setStatus(RevisionStatus.APPROVED);
        rev.setDraftedBy("qa1");
        rev.setDraftedAt(LocalDateTime.now().minusDays(21));
        rev.setApprovedBy("qso1");
        rev.setApprovedAt(LocalDateTime.now().minusDays(20));
        revRepo.save(rev);

        // 2) 新建一份待 QSO 签批的受控文件
        as("qa1", () -> docControl.create(new DocControlService.CreateReq(
                "SOP-V69-02", "V69 留样管理规程", DocCategory.SOP, "质量部", 24,
                "首版起草", today, Set.of(Role.QA))));

        String qaToken = login("qa1");
        JsonNode board = json.readTree(mvc.perform(get("/api/docs/compliance-board")
                        .header("Authorization", "Bearer " + qaToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(board.get("overdueCount").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(board.get("reviewDueCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(board.get("ackPendingCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(board.get("pendingApproveCount").asInt()).isGreaterThanOrEqualTo(1);

        JsonNode reviewRow = findItem(board.get("rows"), "SOP-V69-01", "REVIEW_DUE");
        JsonNode ackRow = findItem(board.get("rows"), "SOP-V69-01", "ACK_PENDING");
        JsonNode pendingRow = findItem(board.get("rows"), "SOP-V69-02", "PENDING_APPROVAL");
        assertThat(reviewRow).isNotNull();
        assertThat(reviewRow.get("riskLevel").asText()).isEqualTo("OVERDUE");
        assertThat(ackRow).isNotNull();
        assertThat(ackRow.get("riskLevel").asText()).isEqualTo("OVERDUE");
        assertThat(pendingRow).isNotNull();
        assertThat(pendingRow.get("riskLevel").asText()).isEqualTo("PENDING");

        byte[] xlsx = mvc.perform(get("/api/reports/doc-compliance").header("Authorization", "Bearer " + qaToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(xlsx.length).isGreaterThan(100);
    }

    private static JsonNode findItem(JsonNode arr, String docCode, String issueType) {
        for (JsonNode n : arr) {
            if (docCode.equals(n.get("docCode").asText()) && issueType.equals(n.get("issueType").asText())) {
                return n;
            }
        }
        return null;
    }
}
