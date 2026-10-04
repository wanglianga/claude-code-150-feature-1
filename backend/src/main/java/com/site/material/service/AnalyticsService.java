package com.site.material.service;

import com.site.material.domain.*;
import com.site.material.repo.*;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 分析与报表：
 *  - 真实材料消耗（项目经理：按楼栋/工序/班组）
 *  - 材料偏差（预算 vs 实际净消耗）
 *  - 损耗复盘（材料员：设计变更/施工错误/保管不当/供应商质量）
 *  - 采购计划反推（预算员：以实际消耗推算后续采购与分包结算量）
 *  - 财务汇总（供应商结算、班组扣款、变更索赔）
 */
@Service
public class AnalyticsService {

    private final RequisitionRepository reqRepo;
    private final MaterialReturnRepository returnRepo;
    private final WasteRepository wasteRepo;
    private final MaterialBatchRepository batchRepo;
    private final ProjectBudgetRepository budgetRepo;
    private final MaterialRepository materialRepo;
    private final SupplierRepository supplierRepo;
    private final CrewRepository crewRepo;
    private final ChangeVisaRepository visaRepo;
    private final SettlementRepository settlementRepo;

    public AnalyticsService(RequisitionRepository reqRepo, MaterialReturnRepository returnRepo,
                            WasteRepository wasteRepo, MaterialBatchRepository batchRepo,
                            ProjectBudgetRepository budgetRepo, MaterialRepository materialRepo,
                            SupplierRepository supplierRepo, CrewRepository crewRepo,
                            ChangeVisaRepository visaRepo, SettlementRepository settlementRepo) {
        this.reqRepo = reqRepo;
        this.returnRepo = returnRepo;
        this.wasteRepo = wasteRepo;
        this.batchRepo = batchRepo;
        this.budgetRepo = budgetRepo;
        this.materialRepo = materialRepo;
        this.supplierRepo = supplierRepo;
        this.crewRepo = crewRepo;
        this.visaRepo = visaRepo;
        this.settlementRepo = settlementRepo;
    }

    private record Key(String building, String process, Long crewId, Long materialId) {}

    /** 消耗/偏差主表，PM 与预算员共用 */
    public List<Map<String, Object>> consumption(String building, String process, Long crewId) {
        Map<Long, Material> mats = materialRepo.findAll().stream()
                .collect(Collectors.toMap(Material::getId, x -> x));
        Map<Long, String> crewNames = crewRepo.findAll().stream()
                .collect(Collectors.toMap(Crew::getId, Crew::getName));

        Map<Key, double[]> agg = new HashMap<>(); // [issuedQty, issuedAmt, returnQty, returnAmt, wasteQty, wasteAmt]

        for (Requisition r : reqRepo.findAllWithItems()) {
            if (!"ISSUED".equals(r.getStatus())) continue;
            if (building != null && !building.equals(r.getBuilding())) continue;
            if (process != null && !process.equals(r.getProcess())) continue;
            if (crewId != null && !crewId.equals(r.getCrewId())) continue;
            for (RequisitionItem i : r.getItems()) {
                Key k = new Key(r.getBuilding(), r.getProcess(), r.getCrewId(), i.getMaterialId());
                double[] a = agg.computeIfAbsent(k, x -> new double[6]);
                double q = nz(i.getIssuedQty());
                a[0] += q;
                a[1] += q * nz(i.getIssuePrice());
            }
        }
        for (MaterialReturn r : returnRepo.findAllWithItems()) {
            if (!"CONFIRMED".equals(r.getStatus())) continue;
            if (building != null && !building.equals(r.getBuilding())) continue;
            if (process != null && !process.equals(r.getProcess())) continue;
            if (crewId != null && !crewId.equals(r.getCrewId())) continue;
            for (ReturnItem i : r.getItems()) {
                if (!"GOOD".equals(i.getConditionType())) continue;
                Key k = new Key(r.getBuilding(), r.getProcess(), r.getCrewId(), i.getMaterialId());
                double[] a = agg.computeIfAbsent(k, x -> new double[6]);
                a[2] += i.getQty();
                a[3] += i.getQty() * nz(i.getPrice());
            }
        }
        for (WasteRecord w : wasteRepo.findAllByOrderByCreatedTimeDesc()) {
            if (building != null && !building.equals(w.getBuilding())) continue;
            if (process != null && !process.equals(w.getProcess())) continue;
            if (crewId != null && !crewId.equals(w.getCrewId())) continue;
            Key k = new Key(w.getBuilding(), w.getProcess(), w.getCrewId(), w.getMaterialId());
            double[] a = agg.computeIfAbsent(k, x -> new double[6]);
            a[4] += w.getQty();
            a[5] += w.getQty() * nz(w.getUnitPrice());
        }

        // 预算量（楼栋+工序+材料，归口班组或共用）
        Map<Long, List<ProjectBudget>> budgetIndex = budgetRepo.findAll().stream()
                .collect(Collectors.groupingBy(ProjectBudget::getMaterialId));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Key, double[]> e : agg.entrySet()) {
            Key k = e.getKey();
            double[] a = e.getValue();
            double netQty = a[0] - a[2];
            double netAmt = a[1] - a[3];
            List<ProjectBudget> bs = budgetIndex.getOrDefault(k.materialId(), List.of()).stream()
                    .filter(b -> Objects.equals(b.getBuilding(), k.building()))
                    .filter(b -> Objects.equals(b.getProcess(), k.process()))
                    .filter(b -> b.getCrewId() == null || b.getCrewId().equals(k.crewId()))
                    .toList();
            double budgetQty = bs.stream().mapToDouble(ProjectBudget::getBudgetQty).sum();
            double budgetAmt = bs.stream().mapToDouble(b -> b.getBudgetQty() * nz(b.getBudgetPrice())).sum();
            Material m = mats.get(k.materialId());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("building", k.building());
            row.put("process", k.process());
            row.put("crewId", k.crewId());
            row.put("crewName", k.crewId() == null ? null : crewNames.get(k.crewId()));
            row.put("materialId", k.materialId());
            row.put("materialName", m == null ? null : m.getName());
            row.put("category", m == null ? null : m.getCategory());
            row.put("unit", m == null ? null : m.getUnit());
            row.put("budgetQty", r2(budgetQty));
            row.put("budgetAmount", r2(budgetAmt));
            row.put("issuedQty", r2(a[0]));
            row.put("issuedAmount", r2(a[1]));
            row.put("returnedQty", r2(a[2]));
            row.put("returnedAmount", r2(a[3]));
            row.put("wasteQty", r2(a[4]));
            row.put("wasteAmount", r2(a[5]));
            row.put("netConsumedQty", r2(netQty));
            row.put("netConsumedAmount", r2(netAmt));
            row.put("varianceQty", r2(netQty - budgetQty));
            row.put("varianceAmount", r2(netAmt - budgetAmt));
            row.put("varianceRate", budgetQty > 0
                    ? r2((netQty - budgetQty) / budgetQty * 100) : null);
            rows.add(row);
        }
        rows.sort(Comparator.comparing((Map<String, Object> x) -> String.valueOf(x.get("building")))
                .thenComparing(x -> String.valueOf(x.get("process"))));
        return rows;
    }

    /** 材料员月底损耗复盘：按原因类别 + 责任归属 */
    public Map<String, Object> wasteReview() {
        List<WasteRecord> wastes = wasteRepo.findAllByOrderByCreatedTimeDesc();
        Map<Long, String> matNames = materialRepo.findAll().stream()
                .collect(Collectors.toMap(Material::getId, Material::getName));
        Map<Long, String> crewNames = crewRepo.findAll().stream()
                .collect(Collectors.toMap(Crew::getId, Crew::getName));

        String[] causeOrder = {"DESIGN", "CONSTRUCTION", "STORAGE", "QUALITY", "OTHER"};
        Map<String, String> causeName = Map.of(
                "DESIGN", "设计变更", "CONSTRUCTION", "施工错误",
                "STORAGE", "保管不当", "QUALITY", "供应商质量", "OTHER", "其他");
        // 损耗类型 -> 复盘原因归类
        Map<String, String> typeToCause = Map.of(
                "BREAKAGE", "CONSTRUCTION",
                "EXPIRED", "STORAGE",
                "STORAGE", "STORAGE",
                "CONSTRUCTION", "CONSTRUCTION",
                "CUT", "CONSTRUCTION",
                "QUALITY", "QUALITY");

        Map<String, double[]> causeAgg = new LinkedHashMap<>();
        for (String c : causeOrder) causeAgg.put(c, new double[2]); // 次数, 金额
        List<Map<String, Object>> detail = new ArrayList<>();
        for (WasteRecord w : wastes) {
            String cause;
            if ("DESIGN".equals(w.getResponsibility())) cause = "DESIGN";
            else if ("SUPPLIER".equals(w.getResponsibility())) cause = "QUALITY";
            else cause = typeToCause.getOrDefault(w.getWasteType(), "OTHER");
            double amount = w.getQty() * nz(w.getUnitPrice());
            causeAgg.get(cause)[0] += 1;
            causeAgg.get(cause)[1] += amount;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", w.getCode());
            row.put("materialName", matNames.get(w.getMaterialId()));
            row.put("crewName", w.getCrewId() == null ? null : crewNames.get(w.getCrewId()));
            row.put("building", w.getBuilding());
            row.put("process", w.getProcess());
            row.put("qty", w.getQty());
            row.put("amount", r2(amount));
            row.put("wasteType", w.getWasteType());
            row.put("responsibility", w.getResponsibility());
            row.put("cause", cause);
            row.put("causeName", causeName.get(cause));
            row.put("description", w.getDescription());
            row.put("createdTime", w.getCreatedTime());
            detail.add(row);
        }
        List<Map<String, Object>> summary = new ArrayList<>();
        double total = 0;
        for (String c : causeOrder) {
            double[] v = causeAgg.get(c);
            total += v[1];
            summary.add(Map.of("cause", c, "causeName", causeName.get(c),
                    "count", (int) v[0], "amount", r2(v[1])));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalAmount", r2(total));
        out.put("summary", summary);
        out.put("detail", detail);
        return out;
    }

    /** 预算员：以实际消耗反推后续采购计划 */
    public Map<String, Object> procurementPlan(String building) {
        List<Material> mats = materialRepo.findAll();
        List<MaterialBatch> batches = batchRepo.findAll();
        Map<Long, Double> onHand = new HashMap<>();
        Map<Long, Double> avgPrice = new HashMap<>();
        Map<Long, Long> lastSupplier = new HashMap<>();
        for (Material m : mats) {
            List<MaterialBatch> bs = batches.stream()
                    .filter(b -> b.getMaterialId().equals(m.getId())).toList();
            onHand.put(m.getId(), bs.stream().filter(b -> !"FAILED".equals(b.getQcStatus()))
                    .mapToDouble(MaterialBatch::onHand).sum());
            avgPrice.put(m.getId(), bs.stream().mapToDouble(MaterialBatch::getUnitPrice).average().orElse(0d));
            bs.stream().max(Comparator.comparing(b -> b.getInboundTime() == null ? LocalDateTime.MIN : b.getInboundTime()))
                    .ifPresent(b -> lastSupplier.put(m.getId(), b.getSupplierId()));
        }
        Map<Long, String> supplierNames = supplierRepo.findAll().stream()
                .collect(Collectors.toMap(Supplier::getId, Supplier::getName));

        // 预算总量、净耗量（可按楼栋过滤）
        Map<Long, double[]> perMaterial = new HashMap<>(); // budgetQty, netConsumed
        for (ProjectBudget b : budgetRepo.findAll()) {
            if (building != null && !building.equals(b.getBuilding())) continue;
            perMaterial.computeIfAbsent(b.getMaterialId(), x -> new double[2])[0] += b.getBudgetQty();
        }
        for (Map<String, Object> row : consumption(building, null, null)) {
            Long mid = (Long) row.get("materialId");
            double net = ((Number) row.get("netConsumedQty")).doubleValue();
            perMaterial.computeIfAbsent(mid, x -> new double[2])[1] += net;
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Material m : mats) {
            double[] v = perMaterial.getOrDefault(m.getId(), new double[2]);
            double budgetQty = v[0], consumed = v[1];
            double remaining = Math.max(0d, budgetQty - consumed);
            double stock = onHand.getOrDefault(m.getId(), 0d);
            double suggest = Math.max(0d, remaining - stock);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("materialId", m.getId());
            row.put("materialName", m.getName());
            row.put("category", m.getCategory());
            row.put("spec", m.getSpec());
            row.put("unit", m.getUnit());
            row.put("budgetQty", r2(budgetQty));
            row.put("consumedQty", r2(consumed));
            row.put("remainingDemand", r2(remaining));
            row.put("usableStock", r2(stock));
            row.put("suggestPurchaseQty", r2(suggest));
            row.put("avgPrice", r2(avgPrice.getOrDefault(m.getId(), 0d)));
            row.put("suggestAmount", r2(suggest * avgPrice.getOrDefault(m.getId(), 0d)));
            Long sid = lastSupplier.get(m.getId());
            row.put("suggestSupplierId", sid);
            row.put("suggestSupplier", sid == null ? null : supplierNames.get(sid));
            // 供应商补货延迟提示：有质检失败批次或库存低于安全库存且仍有后续需求
            boolean delayed = batches.stream().anyMatch(b -> b.getMaterialId().equals(m.getId())
                    && "FAILED".equals(b.getQcStatus()));
            row.put("supplierDelayRisk", delayed || (suggest > 0
                    && stock <= nz2(m.getSafetyStock())));
            rows.add(row);
        }
        double totalSuggest = rows.stream().mapToDouble(x -> ((Number) x.get("suggestAmount")).doubleValue()).sum();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("building", building);
        out.put("totalSuggestAmount", r2(totalSuggest));
        out.put("rows", rows);
        return out;
    }

    /** 财务：供应商结算 / 班组扣款 / 变更索赔 */
    public Map<String, Object> financeSummary() {
        // 供应商：入库金额、质检不合格金额（可索赔/扣款）、待检金额
        Map<Long, Supplier> suppliers = supplierRepo.findAll().stream()
                .collect(Collectors.toMap(Supplier::getId, x -> x));
        Map<Long, double[]> supAgg = new HashMap<>(); // inboundAmt, failedAmt, inboundQty
        Map<Long, Long> batchSupplier = new HashMap<>();
        for (MaterialBatch b : batchRepo.findAll()) {
            if (b.getSupplierId() == null) continue;
            double[] a = supAgg.computeIfAbsent(b.getSupplierId(), x -> new double[3]);
            a[0] += b.getQtyInbound() * nz(b.getUnitPrice());
            a[2] += b.getQtyInbound();
            if ("FAILED".equals(b.getQcStatus())) a[1] += b.getQtyInbound() * nz(b.getUnitPrice());
        }
        // 供应商质量损耗索赔（waste SUPPLIER）
        double supplierQualityClaim = wasteRepo.findAllByOrderByCreatedTimeDesc().stream()
                .filter(w -> "SUPPLIER".equals(w.getResponsibility()))
                .mapToDouble(w -> w.getQty() * nz(w.getUnitPrice())).sum();

        List<Map<String, Object>> supplierRows = new ArrayList<>();
        for (Map.Entry<Long, double[]> e : supAgg.entrySet()) {
            Supplier s = suppliers.get(e.getKey());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("supplierId", e.getKey());
            row.put("supplierName", s == null ? null : s.getName());
            row.put("inboundAmount", r2(e.getValue()[0]));
            row.put("qcRejectedAmount", r2(e.getValue()[1]));
            row.put("payableAmount", r2(e.getValue()[0] - e.getValue()[1]));
            supplierRows.add(row);
        }

        // 班组扣款（最新月份核算单口径）
        List<Settlement> settlements = settlementRepo.findAllByOrderByPeriodDescIdAsc();
        Map<Long, Settlement> latestByCrew = new LinkedHashMap<>();
        for (Settlement s : settlements) {
            latestByCrew.putIfAbsent(s.getCrewId(), s);
        }
        Map<Long, String> crewNames = crewRepo.findAll().stream()
                .collect(Collectors.toMap(Crew::getId, Crew::getName));
        List<Map<String, Object>> crewRows = new ArrayList<>();
        double totalDeduction = 0, totalClaim = 0;
        for (Settlement s : latestByCrew.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("crewId", s.getCrewId());
            row.put("crewName", crewNames.get(s.getCrewId()));
            row.put("period", s.getPeriod());
            row.put("consumedAmount", s.getConsumedAmount());
            row.put("crewWasteAmount", s.getCrewWasteAmount());
            row.put("varianceAmount", s.getVarianceAmount());
            row.put("deductionAmount", s.getDeductionAmount());
            row.put("claimAmount", s.getClaimAmount());
            row.put("status", s.getStatus());
            crewRows.add(row);
            totalDeduction += nz(s.getDeductionAmount());
            totalClaim += nz(s.getClaimAmount());
        }

        // 变更签证索赔台账
        List<Map<String, Object>> visaRows = visaRepo.findAllByOrderByCreatedTimeDesc().stream().map(v -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", v.getCode());
            row.put("building", v.getBuilding());
            row.put("process", v.getProcess());
            row.put("changeType", v.getChangeType());
            row.put("description", v.getDescription());
            row.put("claimAmount", r2(nz(v.getClaimAmount())));
            row.put("status", v.getStatus());
            row.put("createdTime", v.getCreatedTime());
            return row;
        }).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("supplierTotalInbound", r2(supplierRows.stream()
                .mapToDouble(x -> ((Number) x.get("inboundAmount")).doubleValue()).sum()));
        out.put("supplierTotalPayable", r2(supplierRows.stream()
                .mapToDouble(x -> ((Number) x.get("payableAmount")).doubleValue()).sum()));
        out.put("supplierQualityClaim", r2(supplierQualityClaim));
        out.put("crewTotalDeduction", r2(totalDeduction));
        out.put("visaTotalClaim", r2(totalClaim));
        out.put("suppliers", supplierRows);
        out.put("crews", crewRows);
        out.put("visas", visaRows);
        return out;
    }

    private static double nz(Double v) {
        return v == null ? 0d : v;
    }

    private static double nz2(Double v) {
        return v == null ? 0d : v;
    }

    private static double r2(double v) {
        return Math.round(v * 100d) / 100d;
    }
}
