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
 * 损耗核销：瓷砖破损、砂浆过期、保管不当、施工错误、供应商质量问题。
 * 核销批次库存，记录责任归属，供月底班组核算与材料员复盘使用。
 */
@Service
public class WasteService {

    private final WasteRepository wasteRepo;
    private final MaterialBatchRepository batchRepo;
    private final MaterialRepository materialRepo;
    private final CrewRepository crewRepo;
    private final CodeGenerator codes;

    private static final Set<String> RESPONSIBILITIES =
            Set.of("CREW", "SUPPLIER", "DESIGN", "COMPANY", "NATURE");

    public WasteService(WasteRepository wasteRepo, MaterialBatchRepository batchRepo,
                        MaterialRepository materialRepo, CrewRepository crewRepo, CodeGenerator codes) {
        this.wasteRepo = wasteRepo;
        this.batchRepo = batchRepo;
        this.materialRepo = materialRepo;
        this.crewRepo = crewRepo;
        this.codes = codes;
    }

    @Transactional
    public WasteRecord create(WasteRequest req) {
        if (!RESPONSIBILITIES.contains(req.responsibility())) {
            throw new BusinessException("责任归属非法: " + req.responsibility());
        }
        Material m = materialRepo.findById(req.materialId())
                .orElseThrow(() -> new BusinessException("材料不存在"));
        MaterialBatch batch = null;
        if (req.batchId() != null) {
            batch = batchRepo.findById(req.batchId())
                    .orElseThrow(() -> new BusinessException("批次不存在"));
            if (batch.onHand() + 1e-9 < req.qty()) {
                throw new BusinessException("批次现场库存不足，无法核销损耗，账面: "
                        + MasterDataService.round(batch.onHand()));
            }
        }
        WasteRecord w = new WasteRecord();
        w.setCode(codes.next("SH"));
        w.setMaterialId(req.materialId());
        w.setBatchId(req.batchId());
        w.setCrewId(req.crewId());
        w.setBuilding(req.building());
        w.setArea(req.area());
        w.setProcess(req.process());
        w.setQty(req.qty());
        w.setUnitPrice(batch != null ? batch.getUnitPrice() : 0d);
        w.setWasteType(req.wasteType());
        w.setResponsibility(req.responsibility());
        w.setVisaId(req.visaId());
        w.setDescription(req.description());
        w.setRecorder(SecurityUtil.currentName());
        w.setCreatedTime(LocalDateTime.now());
        WasteRecord saved = wasteRepo.save(w);

        if (batch != null) {
            batch.setQtyWasted(batch.getQtyWasted() + req.qty());
            batchRepo.save(batch);
        }
        return saved;
    }

    public List<Map<String, Object>> list() {
        Map<Long, String> matNames = materialRepo.findAll().stream()
                .collect(Collectors.toMap(Material::getId, Material::getName));
        Map<Long, String> units = materialRepo.findAll().stream()
                .collect(Collectors.toMap(Material::getId, Material::getUnit));
        Map<Long, String> crewNames = crewRepo.findAll().stream()
                .collect(Collectors.toMap(Crew::getId, Crew::getName));
        return wasteRepo.findAllByOrderByCreatedTimeDesc().stream().map(w -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", w.getId());
            x.put("code", w.getCode());
            x.put("materialId", w.getMaterialId());
            x.put("materialName", matNames.get(w.getMaterialId()));
            x.put("unit", units.get(w.getMaterialId()));
            x.put("batchId", w.getBatchId());
            x.put("crewId", w.getCrewId());
            x.put("crewName", w.getCrewId() == null ? null : crewNames.get(w.getCrewId()));
            x.put("building", w.getBuilding());
            x.put("area", w.getArea());
            x.put("process", w.getProcess());
            x.put("qty", w.getQty());
            x.put("unitPrice", w.getUnitPrice());
            x.put("amount", MasterDataService.round(w.getQty() * w.getUnitPrice()));
            x.put("wasteType", w.getWasteType());
            x.put("responsibility", w.getResponsibility());
            x.put("visaId", w.getVisaId());
            x.put("returnId", w.getReturnId());
            x.put("description", w.getDescription());
            x.put("recorder", w.getRecorder());
            x.put("createdTime", w.getCreatedTime());
            return x;
        }).toList();
    }
}
