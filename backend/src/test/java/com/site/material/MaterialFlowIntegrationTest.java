package com.site.material;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 全链路集成测试（H2 内存库 + 种子数据）：
 * 登录 -> 发料核对（预算/已领/库存）-> 紧急发料 -> 退库确认 -> 损耗核销 ->
 * 盘点调账 -> 月底核算 -> 偏差/复盘/财务分析。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MaterialFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    private String token(String username, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return om.readTree(body).get("token").asText();
    }

    @Test
    void fullMaterialLifecycleAndSettlement() throws Exception {
        String keeper = token("keeper", "keeper123");
        String finance = token("finance", "finance123");
        String foreman = token("foreman5", "foreman123");

        // 1. 库存总览有数据
        mvc.perform(get("/api/inventory").header("Authorization", "Bearer " + keeper))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].materialName").exists());

        // 2. 待发料单：夜间紧急单（p3 机电班 DN20 600 米）核对，库存 2710（3000-400+130-20）
        JsonNode reqs = om.readTree(mvc.perform(get("/api/requisitions?status=SUBMITTED")
                        .header("Authorization", "Bearer " + keeper))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long urgentId = -1;
        for (JsonNode r : reqs) {
            if (r.get("urgent").asBoolean()) urgentId = r.get("id").asLong();
        }
        assertTrue(urgentId > 0, "应存在夜间紧急领料单");

        JsonNode pre = om.readTree(mvc.perform(get("/api/requisitions/" + urgentId + "/precheck")
                        .header("Authorization", "Bearer " + keeper))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        JsonNode check = pre.get("checks").get(0);
        assertTrue(check.get("stockEnough").asBoolean(), "紧急单库存应充足");
        assertEquals(2000d, check.get("budgetQty").asDouble(), 0.01);
        assertEquals(400d, check.get("alreadyIssued").asDouble(), 0.01);

        // 3. 仓管发料：选择第一个可用批次
        long itemId = check.get("itemId").asLong();
        long batchId = check.get("batches").get(0).get("batchId").asLong();
        String issueBody = om.writeValueAsString(Map.of("items",
                List.of(Map.of("itemId", itemId, "batchId", batchId, "issuedQty", 600d))));
        mvc.perform(put("/api/requisitions/" + urgentId + "/issue")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON).content(issueBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requisition.status").value("ISSUED"));

        // 4. 库存不足时发料应被拒绝（瓷砖班申请 40 箱，库存 875 箱 => 充足；改用超量构造失败场景：钢筋班 p1 仅 3 吨库存 38 吨，不触发。
        //    直接发一张不存在的超量：用 p1 发 999 吨应报库存不足）
        String p1Raw = mvc.perform(get("/api/requisitions?status=SUBMITTED")
                        .header("Authorization", "Bearer " + keeper))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode p1List = om.readTree(p1Raw);
        long steelId = -1, steelItem = -1, steelBatch = -1;
        for (JsonNode r : p1List) {
            if ("主体结构".equals(r.get("process").asText()) && r.get("crewId").asLong() == 1) {
                steelId = r.get("id").asLong();
                steelItem = r.get("items").get(0).get("itemId").asLong();
            }
        }
        String steelRaw = mvc.perform(get("/api/requisitions/" + steelId + "/precheck")
                .header("Authorization", "Bearer " + keeper)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode steelPre = om.readTree(steelRaw);
        steelBatch = steelPre.get("checks").get(0).get("batches").get(0).get("batchId").asLong();
        String badIssue = om.writeValueAsString(Map.of("items",
                List.of(Map.of("itemId", steelItem, "batchId", steelBatch, "issuedQty", 999d))));
        mvc.perform(put("/api/requisitions/" + steelId + "/issue")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON).content(badIssue))
                .andExpect(status().isBadRequest());

        // 正常发 3 吨（累计 15 < 20 预算，不超）
        String okIssue = om.writeValueAsString(Map.of("items",
                List.of(Map.of("itemId", steelItem, "batchId", steelBatch, "issuedQty", 3d))));
        mvc.perform(put("/api/requisitions/" + steelId + "/issue")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON).content(okIssue))
                .andExpect(status().isOk());

        // 5. 班组长提交规格领错退换（未使用 20 米 + 现场照片），仓管判定责任后确认入库
        String photo = "data:image/svg+xml;base64," + java.util.Base64.getEncoder().encodeToString(
                "<svg xmlns='http://www.w3.org/2000/svg' width='10' height='10'/>"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String retBody = om.writeValueAsString(Map.of(
                "crewId", 5, "building", "1#楼", "area", "标准层", "process", "机电预埋",
                "reasonType", "SPEC_WRONG",
                "photos", List.of(Map.of("name", "现场照片.jpg", "dataUrl", photo)),
                "items", List.of(Map.of("materialId", 6, "batchId", batchId, "qty", 20d,
                        "conditionType", "GOOD", "price", 3.5))));
        JsonNode ret = om.readTree(mvc.perform(post("/api/returns")
                        .header("Authorization", "Bearer " + foreman)
                        .contentType(MediaType.APPLICATION_JSON).content(retBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long retId = ret.get("id").asLong();
        assertEquals(1, ret.get("photos").size(), "规格领错退换应保存现场照片");
        // 规格领错退换未判定责任 -> 拒绝确认
        mvc.perform(put("/api/returns/" + retId + "/confirm")
                        .header("Authorization", "Bearer " + keeper))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/returns/" + retId + "/confirm")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("faultType", "CREW"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.faultType").value("CREW"));

        // 5b. 规格领错未传照片 -> 拒绝提交；再次退换：未使用 40 米 + 已切割 10 米，
        //     判定仓管发错 -> 完好 40 回补库存，切割 10 自动生成损耗且项目承担（班组不扣款）
        String noPhoto = om.writeValueAsString(Map.of(
                "crewId", 5, "building", "1#楼", "process", "机电预埋", "reasonType", "SPEC_WRONG",
                "items", List.of(Map.of("materialId", 6, "qty", 1d))));
        mvc.perform(post("/api/returns")
                        .header("Authorization", "Bearer " + foreman)
                        .contentType(MediaType.APPLICATION_JSON).content(noPhoto))
                .andExpect(status().isBadRequest());
        String ret2Body = om.writeValueAsString(Map.of(
                "crewId", 5, "building", "1#楼", "area", "标准层", "process", "机电预埋",
                "reasonType", "SPEC_WRONG",
                "photos", List.of(Map.of("name", "切割管段.jpg", "dataUrl", photo)),
                "items", List.of(Map.of("materialId", 6, "batchId", batchId, "qty", 40d, "cutQty", 10d,
                        "conditionType", "GOOD", "price", 3.5))));
        JsonNode ret2 = om.readTree(mvc.perform(post("/api/returns")
                        .header("Authorization", "Bearer " + foreman)
                        .contentType(MediaType.APPLICATION_JSON).content(ret2Body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long ret2Id = ret2.get("id").asLong();
        JsonNode confirmed2 = om.readTree(mvc.perform(put("/api/returns/" + ret2Id + "/confirm")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("faultType", "WAREHOUSE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.faultType").value("WAREHOUSE"))
                .andExpect(jsonPath("$.wasteRefs[0].responsibility").value("COMPANY"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(10d, confirmed2.get("wasteRefs").get(0).get("qty").asDouble(), 0.01,
                "已切割 10 米应自动生成损耗单");

        // 5c. 库存联动：批次 RK20260925001 累计退库 190（种子130 + 20 + 40），累计损耗 30（种子20 + 切割10）
        JsonNode batches = om.readTree(mvc.perform(get("/api/batches")
                        .header("Authorization", "Bearer " + keeper))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        JsonNode b6 = null;
        for (JsonNode b : batches) {
            if ("RK20260925001".equals(b.get("batchNo").asText())) b6 = b;
        }
        assertNotNull(b6, "应存在 DN20 线管批次");
        assertEquals(190d, b6.get("qtyReturned").asDouble(), 0.01);
        assertEquals(30d, b6.get("qtyWasted").asDouble(), 0.01);

        // 5d. 同班组再次申请相同材料：平台提示曾经领错规格
        //     （种子 TK20261002001 退50 + TK20261003001 退80切20 + 本测试 退20 + 退40切10 = 4 次共 220 米）
        JsonNode alerts = om.readTree(mvc.perform(get("/api/requisitions/spec-wrong-alerts")
                        .param("crewId", "5").param("materialId", "6")
                        .header("Authorization", "Bearer " + foreman))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(1, alerts.size());
        assertEquals(4, alerts.get(0).get("count").asInt());
        assertEquals(220d, alerts.get(0).get("totalQty").asDouble(), 0.01);
        String reqBody = om.writeValueAsString(Map.of(
                "crewId", 5, "building", "1#楼", "area", "标准层", "process", "机电预埋",
                "items", List.of(Map.of("materialId", 6, "applyQty", 100d))));
        JsonNode newReq = om.readTree(mvc.perform(post("/api/requisitions")
                        .header("Authorization", "Bearer " + foreman)
                        .contentType(MediaType.APPLICATION_JSON).content(reqBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        JsonNode pre2 = om.readTree(mvc.perform(get("/api/requisitions/" + newReq.get("id").asLong() + "/precheck")
                        .header("Authorization", "Bearer " + keeper))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(4, pre2.get("checks").get(0).get("specWrongCount").asInt(),
                "发料核对应提示该班组曾领错该材料规格");

        // 6. 盘点调账：种子盘点单确认后批次库存变化
        JsonNode checks = om.readTree(mvc.perform(get("/api/stock-checks")
                        .header("Authorization", "Bearer " + keeper))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long checkId = checks.get(0).get("id").asLong();
        mvc.perform(put("/api/stock-checks/" + checkId + "/adjust")
                        .header("Authorization", "Bearer " + keeper))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ADJUSTED"));

        // 7. 月底核算：机电班（crewId=5）2026-10
        String genBody = om.writeValueAsString(Map.of("period", "2026-10", "crewId", 5));
        JsonNode st = om.readTree(mvc.perform(post("/api/settlements/generate")
                        .header("Authorization", "Bearer " + finance)
                        .contentType(MediaType.APPLICATION_JSON).content(genBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long settlementId = st.get("id").asLong();
        // 机电班领用 400*3.5+800*1.2+紧急600*3.5 = 4460；
        // 退库 = 种子(50+80)*3.5 + 本测试退换(20+40)*3.5 = 665
        assertEquals(4460d, st.get("issuedAmount").asDouble(), 0.01);
        assertEquals(665d, st.get("returnedAmount").asDouble(), 0.01);
        assertEquals(3795d, st.get("consumedAmount").asDouble(), 0.01);
        // 预算 2000*3.5 + 3000*1.2 = 10600；净消耗低于预算 => 偏差为负；
        // 扣款 = 班组责任损耗（种子切割 20*3.5=70，班组申请错误）；仓管发错的切割 10 米项目承担，不扣班组
        assertEquals(-6805d, st.get("varianceAmount").asDouble(), 0.01);
        assertEquals(70d, st.get("crewWasteAmount").asDouble(), 0.01);
        assertEquals(70d, st.get("deductionAmount").asDouble(), 0.01);

        // 财务确认核算单
        mvc.perform(put("/api/settlements/" + settlementId + "/confirm")
                        .header("Authorization", "Bearer " + finance))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        // 已确认不可重复生成
        mvc.perform(post("/api/settlements/generate")
                        .header("Authorization", "Bearer " + finance)
                        .contentType(MediaType.APPLICATION_JSON).content(genBody))
                .andExpect(status().isBadRequest());

        // 8. 项目经理：按楼栋/工序/班组查看偏差
        mvc.perform(get("/api/analytics/consumption")
                        .param("building", "1#楼").param("crewId", "5")
                        .header("Authorization", "Bearer " + token("pm", "pm123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].netConsumedAmount").exists());

        // 9. 材料员损耗复盘：四类原因汇总，含设计变更与供应商质量
        JsonNode review = om.readTree(mvc.perform(get("/api/analytics/waste-review")
                        .header("Authorization", "Bearer " + keeper))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(review.get("totalAmount").asDouble() > 0);
        boolean hasDesign = false, hasQuality = false;
        for (JsonNode s : review.get("summary")) {
            if ("DESIGN".equals(s.get("cause").asText()) && s.get("amount").asDouble() > 0) hasDesign = true;
            if ("QUALITY".equals(s.get("cause").asText()) && s.get("amount").asDouble() > 0) hasQuality = true;
        }
        assertTrue(hasDesign && hasQuality, "复盘应同时包含设计变更与供应商质量损耗");

        // 10. 预算员采购计划 + 财务汇总
        mvc.perform(get("/api/analytics/procurement-plan")
                        .param("building", "1#楼")
                        .header("Authorization", "Bearer " + token("budget", "budget123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].suggestPurchaseQty").exists());
        JsonNode fin = om.readTree(mvc.perform(get("/api/analytics/finance")
                        .header("Authorization", "Bearer " + finance))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(fin.get("supplierTotalPayable").asDouble() > 0);
        assertTrue(fin.get("supplierQualityClaim").asDouble() >= 960); // 30*32

        // 11. 权限：班组长不能登记损耗
        mvc.perform(post("/api/wastes")
                        .header("Authorization", "Bearer " + foreman)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "materialId", 4, "building", "1#楼", "qty", 1d,
                                "wasteType", "BREAKAGE", "responsibility", "CREW"))))
                .andExpect(status().isForbidden());

        // 12. 无 token 访问受保护接口 401
        mvc.perform(get("/api/inventory")).andExpect(status().isUnauthorized());
    }
}
