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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 领料链路：班组申请 -> 仓管核对（施工进度/预算量/已领量/现场库存）-> 发料 -> 班组长确认。
 */
@Service
public class RequisitionService {

    private final RequisitionRepository reqRepo;
    private final MaterialBatchRepository batchRepo;
    private final ProjectBudgetRepository budgetRepo;
    private final MaterialRepository materialRepo;
    private final CrewRepository crewRepo;
    private final MaterialReturnRepository returnRepo;
    private final CodeGenerator codes;

    public RequisitionService(RequisitionRepository reqRepo, MaterialBatchRepository batchRepo,
                              ProjectBudgetRepository budgetRepo, MaterialRepository materialRepo,
                              CrewRepository crewRepo, MaterialReturnRepository returnRepo, CodeGenerator codes) {
        this.reqRepo = reqRepo;
        this.batchRepo = batchRepo;
        this.budgetRepo = budgetRepo;
        this.materialRepo = materialRepo;
        this.crewRepo = crewRepo;
        this.returnRepo = returnRepo;
        this.codes = codes;
    }

    @Transactional
    public Requisition create(RequisitionRequest req) {
        if (req.items() == null || req.items().isEmpty()) {
            throw new BusinessException("领料明细不能为空");
        }
        crewRepo.findById(req.crewId()).orElseThrow(() -> new BusinessException("班组不存在"));
        Requisition r = new Requisition();
        r.setCode(codes.next("LL"));
        r.setCrewId(req.crewId());
        r.setBuilding(req.building());
        r.setArea(req.area());
        r.setProcess(req.process());
        r.setDrawingNo(req.drawingNo());
        r.setUrgent(req.urgent());
        r.setRemark(req.remark());
        r.setApplicant(SecurityUtil.currentName());
        r.setApplyTime(LocalDateTime.now());
        r.setStatus("SUBMITTED"); // 班组申请即提交
        for (ItemIn it : req.items()) {
            RequisitionItem item = new RequisitionItem();
            item.setRequisition(r);
            item.setMaterialId(it.materialId());
            item.setApplyQty(it.applyQty());
            r.getItems().add(item);
        }
        return reqRepo.save(r);
    }

    /** 发料前核对：预算量、已领量、现场库存（给前端展示核对结果，不直接改库存） */
    public Map<String, Object> preIssueCheck(Long id) {
        Requisition r = reqRepo.findByIdWithItems(id).orElseThrow(() -> new BusinessException("领料单不存在"));
        Map<Long, Material> mats = materialRepo.findAll().stream()
                .collect(Collectors.toMap(Material::getId, Function.identity()));
        Map<Long, Map<String, Object>> specWrong = specWrongByMaterial(r.getCrewId());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RequisitionItem it : r.getItems()) {
            Material m = mats.get(it.getMaterialId());
            List<MaterialBatch> batches = batchRepo.findByMaterialIdOrderByInboundTimeDesc(it.getMaterialId())
                    .stream().filter(b -> !"FAILED".equals(b.getQcStatus())).toList();
            double onHand = batches.stream().mapToDouble(MaterialBatch::onHand).sum();

            List<ProjectBudget> budgets = budgetRepo.findByBuildingAndProcessAndMaterialId(
                    r.getBuilding(), r.getProcess(), it.getMaterialId());
            double budgetQty = budgets.stream()
                    .filter(b -> b.getCrewId() == null || b.getCrewId().equals(r.getCrewId()))
                    .mapToDouble(ProjectBudget::getBudgetQty).sum();
            double alreadyIssued = issuedAlready(r, it.getMaterialId());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemId", it.getId());
            row.put("materialId", it.getMaterialId());
            row.put("materialName", m == null ? null : m.getName());
            row.put("unit", m == null ? null : m.getUnit());
            row.put("applyQty", it.getApplyQty());
            row.put("budgetQty", MasterDataService.round(budgetQty));
            row.put("alreadyIssued", MasterDataService.round(alreadyIssued));
            row.put("onHandQty", MasterDataService.round(onHand));
            row.put("stockEnough", onHand + 1e-9 >= it.getApplyQty());
            row.put("overBudget", budgetQty > 0 && alreadyIssued + it.getApplyQty() > budgetQty + 1e-9);
            // 同班组曾就该材料发生规格领错 -> 发料核对时提示复核规格
            Map<String, Object> sw = specWrong.get(it.getMaterialId());
            row.put("specWrongCount", sw == null ? 0 : sw.get("count"));
            row.put("specWrongLastTime", sw == null ? null : sw.get("lastTime"));
            row.put("specWrongFaultType", sw == null ? null : sw.get("lastFaultType"));
            row.put("batches", batches.stream().map(b -> Map.of(
                    "batchId", b.getId(), "batchNo", b.getBatchNo(),
                    "onHand", b.onHand(), "unitPrice", b.getUnitPrice(),
                    "qcStatus", b.getQcStatus())).toList());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requisition", view(r));
        out.put("checks", rows);
        return out;
    }

    /** 同班组历史"规格领错"退换记录（按材料聚合）：用于再次申请相同材料时的提醒 */
    public List<Map<String, Object>> specWrongAlerts(Long crewId, Long materialId) {
        Map<Long, Map<String, Object>> byMaterial = specWrongByMaterial(crewId);
        return byMaterial.values().stream()
                .filter(x -> materialId == null || materialId.equals(x.get("materialId")))
                .toList();
    }

    /** 按材料聚合该班组已确认的规格领错退换：次数 / 累计数量 / 最近一次时间与责任判定 */
    private Map<Long, Map<String, Object>> specWrongByMaterial(Long crewId) {
        if (crewId == null) {
            return Map.of();
        }
        Map<Long, String> matNames = materialRepo.findAll().stream()
                .collect(Collectors.toMap(Material::getId, Material::getName));
        Map<Long, String> units = materialRepo.findAll().stream()
                .collect(Collectors.toMap(Material::getId, Material::getUnit));
        Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
        for (MaterialReturn r : returnRepo.findConfirmedSpecWrongByCrew(crewId)) {
            for (ReturnItem i : r.getItems()) {
                Map<String, Object> x = out.computeIfAbsent(i.getMaterialId(), k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("materialId", k);
                    m.put("materialName", matNames.get(k));
                    m.put("unit", units.get(k));
                    m.put("count", 0);
                    m.put("totalQty", 0d);
                    return m;
                });
                x.put("count", (int) x.get("count") + 1);
                x.put("totalQty", MasterDataService.round((double) x.get("totalQty")
                        + (i.getQty() == null ? 0d : i.getQty())
                        + (i.getCutQty() == null ? 0d : i.getCutQty())));
                // 列表按 confirmTime 倒序，首次遇到即为最近一次
                x.putIfAbsent("lastTime", r.getConfirmTime());
                x.putIfAbsent("lastCode", r.getCode());
                x.putIfAbsent("lastFaultType", r.getFaultType());
            }
        }
        return out;
    }

    private double issuedAlready(Requisition current, Long materialId) {
        return reqRepo.findAllWithItems().stream()
                .filter(x -> "ISSUED".equals(x.getStatus()))
                .filter(x -> x.getCrewId().equals(current.getCrewId()))
                .filter(x -> Objects.equals(x.getBuilding(), current.getBuilding()))
                .filter(x -> Objects.equals(x.getProcess(), current.getProcess()))
                .flatMap(x -> x.getItems().stream())
                .filter(i -> i.getMaterialId().equals(materialId))
                .mapToDouble(i -> i.getIssuedQty() == null ? 0d : i.getIssuedQty())
                .sum();
    }

    @Transactional
    public Map<String, Object> issue(Long id, IssueRequest req) {
        Requisition r = reqRepo.findByIdWithItems(id).orElseThrow(() -> new BusinessException("领料单不存在"));
        if (!"SUBMITTED".equals(r.getStatus())) {
            throw new BusinessException("仅待发料单据可执行发料，当前状态: " + r.getStatus());
        }
        Map<Long, IssueItemIn> input = req.items().stream()
                .collect(Collectors.toMap(IssueItemIn::itemId, Function.identity()));
        List<String> warnings = new ArrayList<>();

        for (RequisitionItem item : r.getItems()) {
            IssueItemIn in = input.get(item.getId());
            if (in == null || in.issuedQty() == null || in.issuedQty() <= 0) {
                throw new BusinessException("明细 " + item.getId() + " 未填写实发数量");
            }
            if (in.batchId() == null) {
                throw new BusinessException("请选择发料批次");
            }
            MaterialBatch batch = batchRepo.findById(in.batchId())
                    .orElseThrow(() -> new BusinessException("批次不存在"));
            if (!batch.getMaterialId().equals(item.getMaterialId())) {
                throw new BusinessException("批次材料与领料明细不一致");
            }
            if ("FAILED".equals(batch.getQcStatus()) || "PENDING".equals(batch.getQcStatus())) {
                throw new BusinessException("批次 " + batch.getBatchNo() + " 质检状态为 "
                        + batch.getQcStatus() + "，不能发料");
            }
            if (batch.onHand() + 1e-9 < in.issuedQty()) {
                throw new BusinessException("批次 " + batch.getBatchNo() + " 现场库存不足："
                        + MasterDataService.round(batch.onHand()));
            }
            batch.setQtyIssued(batch.getQtyIssued() + in.issuedQty());
            item.setBatchId(batch.getId());
            item.setIssuedQty(in.issuedQty());
            item.setIssuePrice(batch.getUnitPrice());

            double already = issuedAlready(r, item.getMaterialId());
            double budgetQty = budgetRepo.findByBuildingAndProcessAndMaterialId(
                            r.getBuilding(), r.getProcess(), item.getMaterialId()).stream()
                    .filter(b -> b.getCrewId() == null || b.getCrewId().equals(r.getCrewId()))
                    .mapToDouble(ProjectBudget::getBudgetQty).sum();
            if (budgetQty > 0 && already + in.issuedQty() > budgetQty + 1e-9 && !r.isUrgent()) {
                warnings.add("材料 " + item.getMaterialId() + " 累计领用已超预算，需补办审批");
            }
        }
        r.setStatus("ISSUED");
        r.setIssuedBy(SecurityUtil.currentName());
        r.setConfirmedBy(r.getApplicant()); // 班组长确认领用
        r.setIssueTime(LocalDateTime.now());
        if (req.remark() != null) {
            r.setRemark(req.remark());
        }
        reqRepo.save(r);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requisition", view(r));
        out.put("warnings", warnings);
        return out;
    }

    @Transactional
    public Requisition reject(Long id, String remark) {
        Requisition r = reqRepo.findByIdWithItems(id).orElseThrow(() -> new BusinessException("领料单不存在"));
        if (!"SUBMITTED".equals(r.getStatus())) {
            throw new BusinessException("仅待发料单据可驳回");
        }
        r.setStatus("REJECTED");
        r.setRemark(remark);
        return r;
    }

    public List<Map<String, Object>> list(String status, Long crewId) {
        List<Requisition> all = reqRepo.findAllWithItems();
        return all.stream()
                .filter(r -> status == null || status.equals(r.getStatus()))
                .filter(r -> crewId == null || crewId.equals(r.getCrewId()))
                .map(this::view)
                .toList();
    }

    public Map<String, Object> detail(Long id) {
        Requisition r = reqRepo.findByIdWithItems(id).orElseThrow(() -> new BusinessException("领料单不存在"));
        return view(r);
    }

    public Map<String, Object> view(Requisition r) {
        Map<Long, String> matNames = materialRepo.findAll().stream()
                .collect(Collectors.toMap(Material::getId, Material::getName));
        Map<Long, String> units = materialRepo.findAll().stream()
                .collect(Collectors.toMap(Material::getId, Material::getUnit));
        Map<Long, String> crewNames = crewRepo.findAll().stream()
                .collect(Collectors.toMap(Crew::getId, Crew::getName));
        Map<Long, String> batchNos = batchRepo.findAll().stream()
                .collect(Collectors.toMap(MaterialBatch::getId, MaterialBatch::getBatchNo));

        List<Map<String, Object>> items = r.getItems().stream().map(i -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("itemId", i.getId());
            x.put("materialId", i.getMaterialId());
            x.put("materialName", matNames.get(i.getMaterialId()));
            x.put("unit", units.get(i.getMaterialId()));
            x.put("applyQty", i.getApplyQty());
            x.put("issuedQty", i.getIssuedQty());
            x.put("batchId", i.getBatchId());
            x.put("batchNo", i.getBatchId() == null ? null : batchNos.get(i.getBatchId()));
            x.put("issuePrice", i.getIssuePrice());
            x.put("amount", MasterDataService.round(
                    (i.getIssuedQty() == null ? 0d : i.getIssuedQty()) * i.getIssuePrice()));
            return x;
        }).toList();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("code", r.getCode());
        m.put("crewId", r.getCrewId());
        m.put("crewName", crewNames.get(r.getCrewId()));
        m.put("building", r.getBuilding());
        m.put("area", r.getArea());
        m.put("process", r.getProcess());
        m.put("drawingNo", r.getDrawingNo());
        m.put("status", r.getStatus());
        m.put("urgent", r.isUrgent());
        m.put("applicant", r.getApplicant());
        m.put("issuedBy", r.getIssuedBy());
        m.put("confirmedBy", r.getConfirmedBy());
        m.put("applyTime", r.getApplyTime());
        m.put("issueTime", r.getIssueTime());
        m.put("remark", r.getRemark());
        m.put("items", items);
        m.put("totalAmount", MasterDataService.round(items.stream()
                .mapToDouble(x -> (double) x.get("amount")).sum()));
        return m;
    }
}
