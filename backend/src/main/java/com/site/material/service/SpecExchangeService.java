package com.site.material.service;

import com.site.material.common.BusinessException;
import com.site.material.common.CodeGenerator;
import com.site.material.domain.*;
import com.site.material.dto.Requests.SpecExchangeConfirmRequest;
import com.site.material.dto.Requests.SpecExchangeRequest;
import com.site.material.repo.*;
import com.site.material.security.SecurityUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 规格领错退换料链路：
 *  班组长现场发现规格不对无法安装 -> 发起退换（未使用数量/已切割数量/现场照片）
 *  -> 仓管确认并判定责任：可退部分回补批次可用库存，已切割部分生成损耗记录。
 * 责任口径：
 *  - KEEPER 仓管发错：切割损耗计项目承担（COMPANY），班组不扣款；
 *  - CREW 班组申请错误：切割损耗计班组责任（CREW），成本归入该班组月度核算。
 * 已切割料在发料时已出库，损耗只计成本、不再二次扣减批次库存。
 */
@Service
public class SpecExchangeService {

    /** 每张退换单最多 3 张现场照片，单张 Base64 上限 3MB（约 2MB 原图） */
    private static final int MAX_PHOTOS = 3;
    private static final int MAX_PHOTO_LEN = 3_000_000;

    private final SpecExchangeRepository exchangeRepo;
    private final MaterialBatchRepository batchRepo;
    private final MaterialRepository materialRepo;
    private final CrewRepository crewRepo;
    private final RequisitionRepository reqRepo;
    private final WasteRepository wasteRepo;
    private final CodeGenerator codes;

    public SpecExchangeService(SpecExchangeRepository exchangeRepo, MaterialBatchRepository batchRepo,
                               MaterialRepository materialRepo, CrewRepository crewRepo,
                               RequisitionRepository reqRepo, WasteRepository wasteRepo,
                               CodeGenerator codes) {
        this.exchangeRepo = exchangeRepo;
        this.batchRepo = batchRepo;
        this.materialRepo = materialRepo;
        this.crewRepo = crewRepo;
        this.reqRepo = reqRepo;
        this.wasteRepo = wasteRepo;
        this.codes = codes;
    }

    @Transactional
    public SpecExchange create(SpecExchangeRequest req) {
        crewRepo.findById(req.crewId()).orElseThrow(() -> new BusinessException("班组不存在"));
        Material m = materialRepo.findById(req.materialId())
                .orElseThrow(() -> new BusinessException("材料不存在"));
        double issued = nz(req.issuedQty());
        double unused = nz(req.unusedQty());
        double cut = nz(req.cutQty());
        if (unused <= 0 && cut <= 0) {
            throw new BusinessException("未使用数量与已切割数量不能同时为 0");
        }
        if (unused + cut > issued + 1e-9) {
            throw new BusinessException("未使用+已切割数量不能超过领出数量 " + issued);
        }
        List<String> photos = req.photos() == null ? List.of() : req.photos().stream()
                .filter(Objects::nonNull).filter(s -> !s.isBlank()).toList();
        if (photos.isEmpty()) {
            throw new BusinessException("请至少上传 1 张现场照片");
        }
        if (photos.size() > MAX_PHOTOS) {
            throw new BusinessException("现场照片最多上传 " + MAX_PHOTOS + " 张");
        }
        for (String p : photos) {
            if (p.length() > MAX_PHOTO_LEN) {
                throw new BusinessException("单张现场照片过大，请压缩到 2MB 以内");
            }
        }

        SpecExchange e = new SpecExchange();
        e.setCode(codes.next("TH"));
        e.setCrewId(req.crewId());
        e.setRequisitionId(req.requisitionId());
        e.setBuilding(req.building());
        e.setArea(req.area());
        e.setProcess(req.process());
        e.setMaterialId(req.materialId());
        e.setBatchId(req.batchId());
        e.setWrongSpec(req.wrongSpec() != null && !req.wrongSpec().isBlank() ? req.wrongSpec() : m.getSpec());
        e.setExpectSpec(req.expectSpec());
        e.setIssuedQty(issued);
        e.setUnusedQty(unused);
        e.setCutQty(cut);
        if (photos.size() > 0) e.setPhoto1(photos.get(0));
        if (photos.size() > 1) e.setPhoto2(photos.get(1));
        if (photos.size() > 2) e.setPhoto3(photos.get(2));
        e.setRemark(req.remark());
        e.setApplicant(SecurityUtil.currentName());
        e.setApplyTime(LocalDateTime.now());
        e.setStatus("SUBMITTED");
        return exchangeRepo.save(e);
    }

    @Transactional
    public SpecExchange confirm(Long id, SpecExchangeConfirmRequest req) {
        SpecExchange e = exchangeRepo.findById(id)
                .orElseThrow(() -> new BusinessException("退换单不存在"));
        if (!"SUBMITTED".equals(e.getStatus())) {
            throw new BusinessException("仅待确认退换单可确认，当前状态: " + e.getStatus());
        }
        String responsibility = req.responsibility();
        if (!"KEEPER".equals(responsibility) && !"CREW".equals(responsibility)) {
            throw new BusinessException("责任方只能是 KEEPER（仓管发错）或 CREW（班组申请错误）");
        }
        Material m = materialRepo.findById(e.getMaterialId())
                .orElseThrow(() -> new BusinessException("材料不存在"));

        // 单价快照：优先取原领料单实发价，其次取批次价
        Double unitPrice = null;
        if (e.getRequisitionId() != null) {
            unitPrice = reqRepo.findByIdWithItems(e.getRequisitionId()).stream()
                    .flatMap(r -> r.getItems().stream())
                    .filter(i -> i.getMaterialId().equals(e.getMaterialId()))
                    .map(RequisitionItem::getIssuePrice).findFirst().orElse(null);
        }
        MaterialBatch batch = null;
        if (e.getUnusedQty() > 0) {
            batch = pickBatch(e);
            if (batch == null) {
                throw new BusinessException("未使用材料缺少可回补的有效批次，请联系材料员");
            }
            if (unitPrice == null || unitPrice == 0d) unitPrice = batch.getUnitPrice();
            // 可退部分重新入库，回补可用库存
            batch.setQtyReturned(batch.getQtyReturned() + e.getUnusedQty());
            batchRepo.save(batch);
        }
        if (batch == null && e.getBatchId() != null) {
            batch = batchRepo.findById(e.getBatchId()).orElse(null);
        }
        if ((unitPrice == null || unitPrice == 0d) && batch != null) {
            unitPrice = batch.getUnitPrice();
        }
        if (unitPrice == null) unitPrice = 0d;

        // 已切割部分计损耗：班组申请错误 -> CREW（进班组扣款）；仓管发错 -> COMPANY（班组不扣款）
        Long wasteId = null;
        if (e.getCutQty() > 0) {
            WasteRecord w = new WasteRecord();
            w.setCode(codes.next("SH"));
            w.setMaterialId(e.getMaterialId());
            w.setCrewId(e.getCrewId());
            w.setBuilding(e.getBuilding());
            w.setArea(e.getArea());
            w.setProcess(e.getProcess());
            w.setQty(e.getCutQty());
            w.setUnitPrice(unitPrice);
            w.setWasteType("SPEC_CUT");
            w.setResponsibility("CREW".equals(responsibility) ? "CREW" : "COMPANY");
            w.setDescription("规格领错已切割报废（退换单 " + e.getCode() + "，实收 "
                    + e.getWrongSpec() + " / 需用 " + nz(e.getExpectSpec()) + "）："
                    + ("CREW".equals(responsibility) ? "班组申请错误，成本归班组" : "仓管发错，项目承担、班组不扣款")
                    + (req.faultNote() != null && !req.faultNote().isBlank() ? "；判定说明：" + req.faultNote() : ""));
            w.setRecorder(SecurityUtil.currentName());
            w.setCreatedTime(LocalDateTime.now());
            wasteId = wasteRepo.save(w).getId();
        }

        e.setResponsibility(responsibility);
        e.setFaultNote(req.faultNote());
        e.setWasteId(wasteId);
        e.setStatus("CONFIRMED");
        e.setConfirmedBy(SecurityUtil.currentName());
        e.setConfirmTime(LocalDateTime.now());
        return exchangeRepo.save(e);
    }

    private MaterialBatch pickBatch(SpecExchange e) {
        if (e.getBatchId() != null) {
            return batchRepo.findById(e.getBatchId()).orElse(null);
        }
        return batchRepo.findByMaterialIdOrderByInboundTimeDesc(e.getMaterialId()).stream()
                .findFirst().orElse(null);
    }

    @Transactional
    public SpecExchange reject(Long id) {
        SpecExchange e = exchangeRepo.findById(id)
                .orElseThrow(() -> new BusinessException("退换单不存在"));
        if (!"SUBMITTED".equals(e.getStatus())) {
            throw new BusinessException("仅待确认退换单可驳回");
        }
        e.setStatus("REJECTED");
        e.setConfirmedBy(SecurityUtil.currentName());
        e.setConfirmTime(LocalDateTime.now());
        return exchangeRepo.save(e);
    }

    public List<Map<String, Object>> list(String status, Long crewId) {
        return exchangeRepo.findAllByOrderByApplyTimeDesc().stream()
                .filter(e -> status == null || status.equals(e.getStatus()))
                .filter(e -> crewId == null || crewId.equals(e.getCrewId()))
                .map(this::view).toList();
    }

    /** 同班组再次申请相同材料时的领错预警 */
    public List<Map<String, Object>> warnings(Long crewId, Long materialId) {
        if (crewId == null || materialId == null) return List.of();
        return exchangeRepo.findByCrewIdAndStatusOrderByConfirmTimeDesc(crewId, "CONFIRMED").stream()
                .filter(e -> e.getMaterialId().equals(materialId))
                .map(e -> {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("code", e.getCode());
                    x.put("materialId", e.getMaterialId());
                    x.put("wrongSpec", e.getWrongSpec());
                    x.put("expectSpec", e.getExpectSpec());
                    x.put("cutQty", e.getCutQty());
                    x.put("responsibility", e.getResponsibility());
                    x.put("confirmedBy", e.getConfirmedBy());
                    x.put("confirmTime", e.getConfirmTime());
                    return x;
                }).toList();
    }

    public Map<String, Object> view(SpecExchange e) {
        Map<Long, Material> mats = materialRepo.findAll().stream()
                .collect(Collectors.toMap(Material::getId, x -> x));
        Map<Long, String> crewNames = crewRepo.findAll().stream()
                .collect(Collectors.toMap(Crew::getId, Crew::getName));
        Map<Long, String> batchNos = batchRepo.findAll().stream()
                .collect(Collectors.toMap(MaterialBatch::getId, MaterialBatch::getBatchNo));
        Material m = mats.get(e.getMaterialId());

        double unitPrice = 0d;
        if (e.getBatchId() != null) {
            MaterialBatch b = batchRepo.findAll().stream()
                    .filter(x -> x.getId().equals(e.getBatchId())).findFirst().orElse(null);
            if (b != null) unitPrice = b.getUnitPrice();
        }
        if (unitPrice == 0d && e.getRequisitionId() != null) {
            unitPrice = reqRepo.findByIdWithItems(e.getRequisitionId()).stream()
                    .flatMap(r -> r.getItems().stream())
                    .filter(i -> i.getMaterialId().equals(e.getMaterialId()))
                    .map(RequisitionItem::getIssuePrice).findFirst().orElse(0d);
        }

        Map<String, Object> x = new LinkedHashMap<>();
        x.put("id", e.getId());
        x.put("code", e.getCode());
        x.put("crewId", e.getCrewId());
        x.put("crewName", crewNames.get(e.getCrewId()));
        x.put("requisitionId", e.getRequisitionId());
        x.put("building", e.getBuilding());
        x.put("area", e.getArea());
        x.put("process", e.getProcess());
        x.put("materialId", e.getMaterialId());
        x.put("materialName", m == null ? null : m.getName());
        x.put("unit", m == null ? null : m.getUnit());
        x.put("batchId", e.getBatchId());
        x.put("batchNo", e.getBatchId() == null ? null : batchNos.get(e.getBatchId()));
        x.put("wrongSpec", e.getWrongSpec());
        x.put("expectSpec", e.getExpectSpec());
        x.put("issuedQty", e.getIssuedQty());
        x.put("unusedQty", e.getUnusedQty());
        x.put("cutQty", e.getCutQty());
        x.put("photos", e.photoList());
        x.put("status", e.getStatus());
        x.put("responsibility", e.getResponsibility());
        x.put("faultNote", e.getFaultNote());
        x.put("wasteId", e.getWasteId());
        x.put("applicant", e.getApplicant());
        x.put("confirmedBy", e.getConfirmedBy());
        x.put("applyTime", e.getApplyTime());
        x.put("confirmTime", e.getConfirmTime());
        x.put("remark", e.getRemark());
        x.put("unitPrice", MasterDataService.round(unitPrice));
        x.put("returnAmount", MasterDataService.round(e.getUnusedQty() * unitPrice));
        x.put("cutAmount", MasterDataService.round(e.getCutQty() * unitPrice));
        return x;
    }

    private static double nz(Double v) {
        return v == null ? 0d : v;
    }

    private static String nz(String s) {
        return s == null ? "—" : s;
    }
}
