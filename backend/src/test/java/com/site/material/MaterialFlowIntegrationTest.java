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

    /** 测试用现场照片（1x1 像素 JPEG data URI） */
    private static final String PHOTO =
            "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////2wBDAf//////////////////////////////////////////////////////////////////////////////////////wAARCAABAAEDASIAAhEBAxEB/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/xAAdEAABBAIDAAAAAAAAAAAAAAABAAIDBAUGByEx/9oACAEBAAA/AOkTv7//Z";

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

        // 2. 待发料单：夜间紧急单（p3 机电班 DN20 600 米）核对，库存 2740（3000-400+50 退库+90 退换回补）
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

        // 5. 班组长提交退库，仓管确认入库（规格领错 20 米 DN20）
        String retBody = om.writeValueAsString(Map.of(
                "crewId", 5, "building", "1#楼", "area", "标准层", "process", "机电预埋",
                "reasonType", "SPEC_WRONG",
                "items", List.of(Map.of("materialId", 6, "batchId", batchId, "qty", 20d,
                        "conditionType", "GOOD", "price", 3.5))));
        JsonNode ret = om.readTree(mvc.perform(post("/api/returns")
                        .header("Authorization", "Bearer " + foreman)
                        .contentType(MediaType.APPLICATION_JSON).content(retBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long retId = ret.get("id").asLong();
        mvc.perform(put("/api/returns/" + retId + "/confirm")
                        .header("Authorization", "Bearer " + keeper))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // 5b. 规格领错退换料：仓管确认待处理单 TH20261003002（水电班申请 DN25 错误，未使用40/已切割10）
        //     确认前 DN25 批次（materialId=10）库存 = 2000-50 = 1950
        JsonNode exchanges = om.readTree(mvc.perform(get("/api/spec-exchanges?status=SUBMITTED")
                        .header("Authorization", "Bearer " + keeper))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long exId = -1;
        for (JsonNode e : exchanges) {
            if ("TH20261003002".equals(e.get("code").asText())) exId = e.get("id").asLong();
        }
        assertTrue(exId > 0, "种子数据应包含待确认的规格退换单");

        // 未上传照片的退换申请应被拒绝
        String noPhoto = om.writeValueAsString(Map.ofEntries(
                Map.entry("crewId", 5), Map.entry("building", "1#楼"),
                Map.entry("area", "标准层"), Map.entry("process", "机电预埋"),
                Map.entry("materialId", 6), Map.entry("batchId", batchId),
                Map.entry("wrongSpec", "DN20"), Map.entry("expectSpec", "DN25"),
                Map.entry("issuedQty", 10d), Map.entry("unusedQty", 8d),
                Map.entry("cutQty", 2d), Map.entry("photos", List.of())));
        mvc.perform(post("/api/spec-exchanges")
                        .header("Authorization", "Bearer " + foreman)
                        .contentType(MediaType.APPLICATION_JSON).content(noPhoto))
                .andExpect(status().isBadRequest());

        // 未使用+已切割超过领出数量应被拦截
        String tooMany = om.writeValueAsString(Map.ofEntries(
                Map.entry("crewId", 5), Map.entry("building", "1#楼"),
                Map.entry("area", "标准层"), Map.entry("process", "机电预埋"),
                Map.entry("materialId", 6), Map.entry("wrongSpec", "DN20"),
                Map.entry("expectSpec", "DN25"), Map.entry("issuedQty", 10d),
                Map.entry("unusedQty", 9d), Map.entry("cutQty", 2d),
                Map.entry("photos", List.of(PHOTO))));
        mvc.perform(post("/api/spec-exchanges")
                        .header("Authorization", "Bearer " + foreman)
                        .contentType(MediaType.APPLICATION_JSON).content(tooMany))
                .andExpect(status().isBadRequest());

        // 仓管判定为班组申请错误：可退 40 米重新入库，切割 10 米成本归班组
        JsonNode confirmed = om.readTree(mvc.perform(put("/api/spec-exchanges/" + exId + "/confirm")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "responsibility", "CREW", "faultNote", "班组未按电气系统图申报规格"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("CONFIRMED", confirmed.get("status").asText());
        assertEquals(40d, confirmed.get("unusedQty").asDouble(), 0.01);
        assertEquals(10d, confirmed.get("cutQty").asDouble(), 0.01);
        assertEquals(4.2d, confirmed.get("unitPrice").asDouble(), 0.01);
        assertEquals(168d, confirmed.get("returnAmount").asDouble(), 0.01); // 40*4.2
        assertEquals(42d, confirmed.get("cutAmount").asDouble(), 0.01);     // 10*4.2
        assertTrue(confirmed.get("wasteId").asLong() > 0, "应生成切割损耗记录");
        assertTrue(confirmed.get("photos").size() >= 1, "应回显现场照片");

        // DN25 可用库存回补：1950 + 40 = 1990
        JsonNode batches10 = om.readTree(mvc.perform(get("/api/batches?materialId=10")
                        .header("Authorization", "Bearer " + keeper))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        double onHand10 = 0;
        for (JsonNode b : batches10) onHand10 += b.get("onHand").asDouble();
        assertEquals(1990d, onHand10, 0.01, "可退部分应重新入库回补可用库存");

        // 5c. 再发起一张仓管发错的退换：水电班领 DN20，未使用 8、切割 2，仓管判定 KEEPER
        String keeperFault = om.writeValueAsString(Map.ofEntries(
                Map.entry("crewId", 5), Map.entry("building", "1#楼"),
                Map.entry("area", "标准层"), Map.entry("process", "机电预埋"),
                Map.entry("materialId", 6), Map.entry("batchId", batchId),
                Map.entry("wrongSpec", "DN20"), Map.entry("expectSpec", "DN25"),
                Map.entry("issuedQty", 10d), Map.entry("unusedQty", 8d),
                Map.entry("cutQty", 2d), Map.entry("photos", List.of(PHOTO)),
                Map.entry("remark", "发料货位相邻拿错")));
        JsonNode ex2 = om.readTree(mvc.perform(post("/api/spec-exchanges")
                        .header("Authorization", "Bearer " + foreman)
                        .contentType(MediaType.APPLICATION_JSON).content(keeperFault))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(put("/api/spec-exchanges/" + ex2.get("id").asLong() + "/confirm")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("responsibility", "KEEPER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.responsibility").value("KEEPER"));

        // 同班组再次申请 DN20（materialId=6）应提示曾经领错规格（种子 e1 + 新单共 2 条）
        JsonNode warns = om.readTree(mvc.perform(get("/api/spec-exchanges/warnings?crewId=5&materialId=6")
                        .header("Authorization", "Bearer " + foreman))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(2, warns.size(), "水电班 DN20 应有 2 条已确认领错记录");
        // 班组长无权确认退换单
        mvc.perform(put("/api/spec-exchanges/" + ex2.get("id").asLong() + "/reject")
                        .header("Authorization", "Bearer " + foreman))
                .andExpect(status().isForbidden());

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
        // 机电班领用 400*3.5+800*1.2+DN25 50*4.2+紧急600*3.5 = 4670
        // 退库冲减：普通退库 (50+20)*3.5=245；规格退换可退回补 90*3.5+40*4.2+8*3.5=511；合计 756
        assertEquals(4670d, st.get("issuedAmount").asDouble(), 0.01);
        assertEquals(756d, st.get("returnedAmount").asDouble(), 0.01);
        assertEquals(3914d, st.get("consumedAmount").asDouble(), 0.01);
        // 预算 2000*3.5 + 3000*1.2 = 10600；净消耗低于预算 => 偏差为负
        assertEquals(-6686d, st.get("varianceAmount").asDouble(), 0.01);
        // 班组申请错误切割 10*4.2=42 计入班组责任损耗；仓管发错的切割（35+7）项目承担，不扣款
        assertEquals(42d, st.get("crewWasteAmount").asDouble(), 0.01);
        assertEquals(42d, st.get("deductionAmount").asDouble(), 0.01);

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
