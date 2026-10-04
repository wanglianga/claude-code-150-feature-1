package com.site.material.service;

import com.site.material.common.BusinessException;
import com.site.material.common.CodeGenerator;
import com.site.material.domain.*;
import com.site.material.dto.Requests.*;
import com.site.material.repo.*;
import com.site.material.security.SecurityUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 退库链路：规格领错/临时追加多余/施工变更退料 -> 仓管确认入库 -> 回补批次库存。
 * 损坏退回（DAMAGED）不回补库存，按损耗口径核销，责任在班组。
 * 规格领错退换（SPEC_WRONG）：班组长上传未使用数量、已切割数量与现场照片；
 * 仓管确认时判定责任（WAREHOUSE 仓管发错-班组不扣款 / CREW 班组申请错误-成本归班组），
 * 未使用部分回补库存，已切割部分自动生成 CUT 损耗单并按责任归属入账。
 */
@Service
public class ReturnService {

    private static final Set<String> FAULT_TYPES = Set.of("WAREHOUSE", "CREW");
    private static final int MAX_PHOTOS = 9;
    private static final int MAX_PHOTO_LENGTH = 1_500_000; // 约 1MB base64

    private final MaterialReturnRepository returnRepo;
    private final MaterialBatchRepository batchRepo;
    private final MaterialRepository materialRepo;
    private final CrewRepository crewRepo;
    private final ReturnPhotoRepository photoRepo;
    private final WasteRepository wasteRepo;
    private final CodeGenerator codes;

    public ReturnService(MaterialReturnRepository returnRepo, MaterialBatchRepository batchRepo,
                         MaterialRepository materialRepo, CrewRepository crewRepo,
                         ReturnPhotoRepository photoRepo, WasteRepository wasteRepo, CodeGenerator codes) {
        this.returnRepo = returnRepo;
        this.batchRepo = batchRepo;
        this.materialRepo = materialRepo;
        this.crewRepo = crewRepo;
        this.photoRepo = photoRepo;
        this.wasteRepo = wasteRepo;
        this.codes = codes;
    }

    @Transactional
    public MaterialReturn create(ReturnRequest req) {
        if (req.items() == null || req.items().isEmpty()) {
            throw new BusinessException("退库明细不能为空");
        }
        crewRepo.findById(req.crewId()).orElseThrow(() -> new BusinessException("班组不存在"));
        boolean specWrong = "SPEC_WRONG".equals(req.reasonType());
        if (specWrong && (req.photos() == null || req.photos().isEmpty())) {
            throw new BusinessException("规格领错退换需上传现场照片");
        }
        MaterialReturn r = new MaterialReturn();
        r.setCode(codes.next("TK"));
        r.setCrewId(req.crewId());
        r.setRequisitionId(req.requisitionId());
        r.setBuilding(req.building());
        r.setArea(req.area());
        r.setProcess(req.process());
        r.setReasonType(req.reasonType());
        r.setRemark(req.remark());
        r.setApplicant(SecurityUtil.currentName());
        r.setApplyTime(LocalDateTime.now());
        r.setStatus("SUBMITTED");
        for (ItemIn it : req.items()) {
            double qty = it.qty() == null ? 0d : it.qty();
            double cutQty = it.cutQty() == null ? 0d : it.cutQty();
            if (qty < 0 || cutQty < 0) {
                throw new BusinessException("退库数量不能为负数");
            }
            if (qty + cutQty <= 0) {
                throw new BusinessException("退库数量必须大于 0（未使用与已切割不能同时为 0）");
            }
            if (cutQty > 0 && "DAMAGED".equals(it.conditionType())) {
                throw new BusinessException("同一明细不能既按损坏退回又登记已切割数量");
            }
            ReturnItem item = new ReturnItem();
            item.setMaterialReturn(r);
            item.setMaterialId(it.materialId());
            item.setBatchId(it.batchId());
            item.setQty(qty);
            item.setCutQty(cutQty);
            item.setConditionType(it.conditionType() == null ? "GOOD" : it.conditionType());
            item.setPrice(it.price() == null ? 0d : it.price());
            r.getItems().add(item);
        }
        if (req.photos() != null) {
            if (req.photos().size() > MAX_PHOTOS) {
                throw new BusinessException("现场照片最多 " + MAX_PHOTOS + " 张");
            }
            for (PhotoIn p : req.photos()) {
                if (p.dataUrl() == null || !p.dataUrl().startsWith("data:image/")) {
                    throw new BusinessException("照片格式非法，需为图片");
                }
                if (p.dataUrl().length() > MAX_PHOTO_LENGTH) {
                    throw new BusinessException("照片 " + (p.name() == null ? "" : p.name()) + " 过大，请压缩后上传");
                }
                ReturnPhoto photo = new ReturnPhoto();
                photo.setMaterialReturn(r);
                photo.setName(p.name() == null ? "现场照片" : p.name());
                photo.setDataUrl(p.dataUrl());
                r.getPhotos().add(photo);
            }
        }
        return returnRepo.save(r);
    }

    @Transactional
    public MaterialReturn confirm(Long id, ReturnConfirmRequest req) {
        MaterialReturn r = returnRepo.findByIdWithItems(id)
                .orElseThrow(() -> new BusinessException("退库单不存在"));
        if (!"SUBMITTED".equals(r.getStatus())) {
            throw new BusinessException("仅待确认退库单可确认，当前状态: " + r.getStatus());
        }
        String faultType = req == null ? null : req.faultType();
        boolean specWrong = "SPEC_WRONG".equals(r.getReasonType());
        if (specWrong) {
            if (faultType == null || !FAULT_TYPES.contains(faultType)) {
                throw new BusinessException("规格领错退换需判定责任：WAREHOUSE 仓管发错（班组不扣款）/ CREW 班组申请错误（成本归班组）");
            }
            r.setFaultType(faultType);
        }
        Map<Long, MaterialBatch> batchMap = batchRepo.findAll().stream()
                .collect(Collectors.toMap(MaterialBatch::getId, b -> b));
        for (ReturnItem it : r.getItems()) {
            double qty = nz(it.getQty());
            double cutQty = nz(it.getCutQty());
            MaterialBatch batch = pickBatch(it, batchMap);
            if (qty > 0 && "GOOD".equals(it.getConditionType()) && batch == null) {
                throw new BusinessException("退库材料缺少有效批次，请联系材料员");
            }
            if (batch != null && (it.getPrice() == null || it.getPrice() == 0d)) {
                it.setPrice(batch.getUnitPrice());
            }
            if (qty > 0 && "GOOD".equals(it.getConditionType())) {
                // 完好退库：回补指定批次库存（累加 qtyReturned）
                batch.setQtyReturned(batch.getQtyReturned() + qty);
            }
            // DAMAGED 损坏退回：不回补，由损耗登记环节按班组责任核销
            if (cutQty > 0) {
                // 已切割部分：计入损耗。仓管发错 -> 项目承担(班组不扣款)；班组申请错误 -> 班组责任
                if (batch != null) {
                    batch.setQtyWasted(batch.getQtyWasted() + cutQty);
                }
                WasteRecord w = new WasteRecord();
                w.setCode(codes.next("SH"));
                w.setMaterialId(it.getMaterialId());
                w.setBatchId(batch == null ? it.getBatchId() : batch.getId());
                w.setCrewId(r.getCrewId());
                w.setBuilding(r.getBuilding());
                w.setArea(r.getArea());
                w.setProcess(r.getProcess());
                w.setQty(cutQty);
                w.setUnitPrice(nz(it.getPrice()));
                w.setWasteType("CUT");
                w.setResponsibility("CREW".equals(faultType) ? "CREW" : "COMPANY");
                w.setReturnId(r.getId());
                String faultNote = "CREW".equals(faultType) ? "班组申请错误，成本归班组"
                        : "WAREHOUSE".equals(faultType) ? "仓管发错，班组不扣款" : "项目承担";
                w.setDescription("规格领错退换 " + r.getCode() + "：已切割 " + MasterDataService.round(cutQty)
                        + " 计入损耗（" + faultNote + "）");
                w.setRecorder(SecurityUtil.currentName());
                w.setCreatedTime(LocalDateTime.now());
                wasteRepo.save(w);
            }
        }
        r.setStatus("CONFIRMED");
        r.setConfirmedBy(SecurityUtil.currentName());
        r.setConfirmTime(LocalDateTime.now());
        if (req != null && req.remark() != null && !req.remark().isBlank()) {
            r.setRemark(r.getRemark() == null ? req.remark() : r.getRemark() + "｜确认备注：" + req.remark());
        }
        return returnRepo.save(r);
    }

    private MaterialBatch pickBatch(ReturnItem it, Map<Long, MaterialBatch> batchMap) {
        if (it.getBatchId() != null) {
            return batchMap.get(it.getBatchId());
        }
        return batchMap.values().stream()
                .filter(b -> b.getMaterialId().equals(it.getMaterialId()))
                .findFirst().orElse(null);
    }

    @Transactional
    public MaterialReturn reject(Long id) {
        MaterialReturn r = returnRepo.findByIdWithItems(id)
                .orElseThrow(() -> new BusinessException("退库单不存在"));
        if (!"SUBMITTED".equals(r.getStatus())) {
            throw new BusinessException("仅待确认退库单可驳回");
        }
        r.setStatus("REJECTED");
        return r;
    }

    public MaterialReturn get(Long id) {
        return returnRepo.findByIdWithItems(id)
                .orElseThrow(() -> new BusinessException("退库单不存在"));
    }

    public List<Map<String, Object>> list(String status, Long crewId) {        List<MaterialReturn> all = returnRepo.findAllWithItems().stream()
                .filter(r -> status == null || status.equals(r.getStatus()))
                .filter(r -> crewId == null || crewId.equals(r.getCrewId()))
                .toList();
        List<Long> ids = all.stream().map(MaterialReturn::getId).toList();
        Map<Long, List<ReturnPhoto>> photos = ids.isEmpty() ? Map.of()
                : photoRepo.findByMaterialReturnIdIn(ids).stream()
                        .collect(Collectors.groupingBy(p -> p.getMaterialReturn().getId()));
        Map<Long, List<WasteRecord>> wastes = ids.isEmpty() ? Map.of()
                : wasteRepo.findByReturnIdIn(ids).stream()
                        .collect(Collectors.groupingBy(WasteRecord::getReturnId));
        return all.stream().map(r -> view(r, photos.getOrDefault(r.getId(), List.of()),
                wastes.getOrDefault(r.getId(), List.of()))).toList();
    }

    public Map<String, Object> view(MaterialReturn r) {
        return view(r, photoRepo.findByMaterialReturnId(r.getId()), wasteRepo.findByReturnId(r.getId()));
    }

    private Map<String, Object> view(MaterialReturn r, List<ReturnPhoto> photos, List<WasteRecord> wastes) {
        Map<Long, String> matNames = materialRepo.findAll().stream()
                .collect(Collectors.toMap(Material::getId, Material::getName));
        Map<Long, String> units = materialRepo.findAll().stream()
                .collect(Collectors.toMap(Material::getId, Material::getUnit));
        Map<Long, String> crewNames = crewRepo.findAll().stream()
                .collect(Collectors.toMap(Crew::getId, Crew::getName));
        List<Map<String, Object>> items = r.getItems().stream().map(i -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("materialId", i.getMaterialId());
            x.put("materialName", matNames.get(i.getMaterialId()));
            x.put("unit", units.get(i.getMaterialId()));
            x.put("batchId", i.getBatchId());
            x.put("qty", i.getQty());
            x.put("cutQty", nz(i.getCutQty()));
            x.put("conditionType", i.getConditionType());
            x.put("price", i.getPrice());
            x.put("amount", MasterDataService.round(nz(i.getQty()) * nz(i.getPrice())));
            x.put("cutAmount", MasterDataService.round(nz(i.getCutQty()) * nz(i.getPrice())));
            return x;
        }).toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("code", r.getCode());
        m.put("crewId", r.getCrewId());
        m.put("crewName", crewNames.get(r.getCrewId()));
        m.put("requisitionId", r.getRequisitionId());
        m.put("building", r.getBuilding());
        m.put("area", r.getArea());
        m.put("process", r.getProcess());
        m.put("reasonType", r.getReasonType());
        m.put("faultType", r.getFaultType());
        m.put("status", r.getStatus());
        m.put("applicant", r.getApplicant());
        m.put("confirmedBy", r.getConfirmedBy());
        m.put("applyTime", r.getApplyTime());
        m.put("confirmTime", r.getConfirmTime());
        m.put("remark", r.getRemark());
        m.put("items", items);
        m.put("photos", photos.stream().map(p -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", p.getId());
            x.put("name", p.getName());
            x.put("dataUrl", p.getDataUrl());
            return x;
        }).toList());
        m.put("wasteRefs", wastes.stream().map(w -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("code", w.getCode());
            x.put("qty", w.getQty());
            x.put("responsibility", w.getResponsibility());
            return x;
        }).toList());
        m.put("totalAmount", MasterDataService.round(items.stream()
                .mapToDouble(x -> (double) x.get("amount")).sum()));
        m.put("totalCutAmount", MasterDataService.round(items.stream()
                .mapToDouble(x -> (double) x.get("cutAmount")).sum()));
        return m;
    }

    private static double nz(Double v) {
        return v == null ? 0d : v;
    }
}
