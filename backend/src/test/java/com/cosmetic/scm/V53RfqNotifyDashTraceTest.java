package com.cosmetic.scm;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import com.cosmetic.scm.demo.DemoDataLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** V53 寻源询价看板与导出、消息触达台账、总览待办导出与批次追溯清单导出集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class V53RfqNotifyDashTraceTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String login(String u) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", u, "password", PWD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void rfqSummaryExportsAndTraceExportRespectPermissions() throws Exception {
        String admin = login("admin");
        String qa = login("qa1");

        // 1. 寻源询价汇总与导出（仅价格/采购权限可见，QA 403）
        mvc.perform(get("/api/rfqs/summary").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").isNumber())
                .andExpect(jsonPath("$.sealedCount").isNumber())
                .andExpect(jsonPath("$.waitingAwardCount").isNumber());

        mvc.perform(get("/api/rfqs/summary").header("Authorization", "Bearer " + qa))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/reports/rfqs").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/reports/rfqs").header("Authorization", "Bearer " + qa))
                .andExpect(status().isForbidden());

        // 2. 消息提醒发送记录导出（仅系统管理员可导）
        mvc.perform(get("/api/reports/notification-logs").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/reports/notification-logs").header("Authorization", "Bearer " + qa))
                .andExpect(status().isForbidden());

        // 3. 当前待办清单导出（全员内部角色可导）
        mvc.perform(get("/api/reports/todos").header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk());

        // 4. 批次追溯清单导出
        JsonNode batches = json.readTree(mvc.perform(get("/api/batches").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        if (batches.isArray() && !batches.isEmpty()) {
            String batchNo = batches.get(0).get("batchNo").asText();
            mvc.perform(get("/api/trace/" + batchNo + "/export").header("Authorization", "Bearer " + qa))
                    .andExpect(status().isOk());
        }
    }
}
