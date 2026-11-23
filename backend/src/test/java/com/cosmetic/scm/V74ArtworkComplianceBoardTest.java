package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.ArtworkStatus;
import com.cosmetic.scm.domain.FormulaStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.ProofStatus;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.ItemRepository;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.PartnerRepository;
import com.cosmetic.scm.plm.ArtworkProof;
import com.cosmetic.scm.plm.ArtworkProofRepository;
import com.cosmetic.scm.plm.FormulaVersion;
import com.cosmetic.scm.plm.FormulaVersionRepository;
import com.cosmetic.scm.plm.LabelArtwork;
import com.cosmetic.scm.plm.LabelArtworkRepository;
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

/** V74 标签设计稿与包材打样合规闭环监控看板及报表导出集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-v74;MODE=MySQL;DB_CLOSE_DELAY=-1")
class V74ArtworkComplianceBoardTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TimeProvider time;
    @Autowired ItemRepository items;
    @Autowired PartnerRepository partners;
    @Autowired FormulaVersionRepository formulaVersions;
    @Autowired LabelArtworkRepository artworkRepo;
    @Autowired ArtworkProofRepository proofRepo;

    private String login(String u) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", u, "password", PWD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void complianceBoardTracksPendingArtworkAndProofOverdueAndExportsReport() throws Exception {
        LocalDate today = time.today();
        LocalDateTime now = time.now();
        FormulaVersion effective = formulaVersions.findByStatus(FormulaStatus.EFFECTIVE).get(0);
        Item pk = items.findAll().stream().filter(i -> i.getItemType() == ItemType.PK).findFirst().orElseThrow();
        Partner supplier = partners.findAll().stream().filter(p -> p.getKind() == PartnerKind.SUPPLIER).findFirst().orElseThrow();

        // 1) 提交 8 天未签批的设计稿 -> PENDING_ARTWORK 且 OVERDUE (agingDays = 8 > 5)
        LabelArtwork pendingArt = new LabelArtwork();
        pendingArt.setArtworkNo("LA-V74-PEND");
        pendingArt.setPkItemId(pk.getId());
        pendingArt.setFgItemId(effective.getFgItemId());
        pendingArt.setFormulaVersionId(effective.getId());
        pendingArt.setVersionNo(91);
        pendingArt.setStatus(ArtworkStatus.PENDING);
        pendingArt.setChangeNote("V74 新增警示用语改版");
        pendingArt.setCreatedBy("qa1");
        pendingArt.setCreatedAt(now.minusDays(8));
        artworkRepo.save(pendingArt);

        // 2) 已批准设计稿 + 收到 7 天未签样的打样 -> PENDING_PROOF 且 OVERDUE (agingDays = 7 > 5)
        LabelArtwork approvedArt = new LabelArtwork();
        approvedArt.setArtworkNo("LA-V74-APP");
        approvedArt.setPkItemId(pk.getId());
        approvedArt.setFgItemId(effective.getFgItemId());
        approvedArt.setFormulaVersionId(effective.getId());
        approvedArt.setVersionNo(92);
        approvedArt.setStatus(ArtworkStatus.APPROVED);
        approvedArt.setChangeNote("V74 现行批准版本");
        approvedArt.setCreatedBy("qa1");
        approvedArt.setCreatedAt(now.minusDays(10));
        approvedArt.setApprovedBy("qso1");
        approvedArt.setApprovedAt(now.minusDays(9));
        approvedArt = artworkRepo.save(approvedArt);

        ArtworkProof overdueProof = new ArtworkProof();
        overdueProof.setProofNo("PF-V74-OVER");
        overdueProof.setArtworkId(approvedArt.getId());
        overdueProof.setSupplierId(supplier.getId());
        overdueProof.setRoundNo(1);
        overdueProof.setProofDate(today.minusDays(7));
        overdueProof.setStatus(ProofStatus.PENDING);
        overdueProof.setNote("V74 首次打样样张");
        overdueProof.setCreatedBy("purchase1");
        overdueProof.setCreatedAt(now.minusDays(7));
        proofRepo.save(overdueProof);

        String qaToken = login("qa1");
        JsonNode board = json.readTree(mvc.perform(get("/api/artworks/compliance-board")
                        .header("Authorization", "Bearer " + qaToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(board.get("pendingArtworkCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(board.get("pendingProofCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(board.get("overdueCount").asInt()).isGreaterThanOrEqualTo(2);

        JsonNode artRow = findByRefNo(board.get("rows"), "LA-V74-PEND");
        JsonNode proofRow = findByRefNo(board.get("rows"), "PF-V74-OVER");
        assertThat(artRow).isNotNull();
        assertThat(artRow.get("itemType").asText()).isEqualTo("PENDING_ARTWORK");
        assertThat(artRow.get("overdue").asBoolean()).isTrue();
        assertThat(artRow.get("agingDays").asLong()).isEqualTo(8L);

        assertThat(proofRow).isNotNull();
        assertThat(proofRow.get("itemType").asText()).isEqualTo("PENDING_PROOF");
        assertThat(proofRow.get("overdue").asBoolean()).isTrue();
        assertThat(proofRow.get("agingDays").asLong()).isEqualTo(7L);

        byte[] xlsx = mvc.perform(get("/api/reports/artwork-compliance").header("Authorization", "Bearer " + qaToken))
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
