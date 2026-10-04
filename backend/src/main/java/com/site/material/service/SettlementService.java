package com.site.material.service;

import com.site.material.common.BusinessException;
import com.site.material.common.CodeGenerator;
import com.site.material.domain.*;
import com.site.material.repo.*;
import com.site.material.security.SecurityUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

/**
 * 月底班组材料核算：按 预算/实际领用/退库/损耗/责任归属 生成班组材料成本与扣款。
 * 计算口径（演示规则，可在 README 查看）：
 *  - 实际消耗 = 已发料金额 - 完好退库金额
 *  - 班组扣款 = 班组责任损耗 + 损坏退库 + 超预算消耗（只计正偏差）
 *  - 供应商质量损耗单列索赔，不扣班组；设计变更/公司承担损耗单列。
 */
@Service
public class SettlementService {

    private final SettlementRepository settlementRepo;
    private final RequisitionRepository reqRepo;
    private final MaterialReturnRepository returnRepo;
    private final WasteRepository wasteRepo;
    private final ChangeVisaRepository visaRepo;
    private final ProjectBudgetRepository budgetRepo;
    private final CrewRepository crewRepo;
    private final SpecExchangeRepository exchangeRepo;
    private final MaterialBatchRepository batchRepo;
    private final CodeGenerator codes;

    public SettlementService(SettlementRepository settlementRepo, RequisitionRepository reqRepo,
                             MaterialReturnRepository returnRepo, WasteRepository wasteRepo,
                             ChangeVisaRepository visaRepo, ProjectBudgetRepository budgetRepo,
                             CrewRepository crewRepo, SpecExchangeRepository exchangeRepo,
                             MaterialBatchRepository batchRepo, CodeGenerator codes) {
        this.settlementRepo = settlementRepo;
        this.reqRepo = reqRepo;
        this.returnRepo = returnRepo;
        this.wasteRepo = wasteRepo;
        this.visaRepo = visaRepo;
        this.budgetRepo = budgetRepo;
        this.crewRepo = crewRepo;
        this.exchangeRepo = exchangeRepo;
        this.batchRepo = batchRepo;
        this.codes = codes;
    }

    @Transactional
    public Settlement generate(String period, Long crewId) {
        YearMonth ym = YearMonth.parse(period);
        LocalDateTime from = ym.atDay(1).atStartOfDay();
        LocalDateTime to = ym.plusMonths(1).atDay(1).atStartOfDay();

        Settlement existing = settlementRepo.findByCrewIdAndPeriod(crewId, period).orElse(null);
        if (existing != null && "CONFIRMED".equals(existing.getStatus())) {
            throw new BusinessException("该班组 " + period + " 核算单已确认，不能重复生成");
        }
        Settlement s = existing != null ? existing : new Settlement();
        if (existing == null) {
            s.setCode(codes.next("HS"));
            s.setCrewId(crewId);
            s.setPeriod(period);
        }

        // 预算金额：归口班组或班组共用的预算行
        double budgetAmount = budgetRepo.findAll().stream()
                .filter(b -> b.getCrewId() == null || b.getCrewId().equals(crewId))
                .mapToDouble(b -> b.getBudgetQty() * b.getBudgetPrice()).sum();

        // 实际领用
        double issuedAmount = reqRepo.findIssuedByCrewBetween(crewId, from, to).stream()
                .flatMap(r -> r.getItems().stream())
                .mapToDouble(i -> nz(i.getIssuedQty()) * nz(i.getIssuePrice())).sum();

        // 退库：GOOD 冲减消耗；DAMAGED 计入班组责任损耗
        double returnedAmount = 0d;
        double damagedAmount = 0d;
        for (MaterialReturn r : returnRepo.findConfirmedByCrewBetween(crewId, from, to)) {
            for (ReturnItem i : r.getItems()) {
                double amt = i.getQty() * nz(i.getPrice());
                if ("GOOD".equals(i.getConditionType())) {
                    returnedAmount += amt;
                } else {
                    damagedAmount += amt;
                }
            }
        }

        // 规格领错退换：未使用部分已重新入库，按原领出单价冲减消耗；
        // 已切割部分在确认时已生成损耗记录（CREW/COMPANY），随下面的损耗口径归属，不重复计。
        Map<Long, Double> batchPrice = new HashMap<>();
        for (com.site.material.domain.SpecExchange e :
                exchangeRepo.findConfirmedByCrewBetween(crewId, from, to)) {
            if (e.getUnusedQty() <= 0) continue;
            double price = 0d;
            if (e.getRequisitionId() != null) {
                price = reqRepo.findByIdWithItems(e.getRequisitionId()).stream()
                        .flatMap(r -> r.getItems().stream())
                        .filter(i -> i.getMaterialId().equals(e.getMaterialId()))
                        .map(RequisitionItem::getIssuePrice).findFirst().orElse(0d);
            }
            if (price == 0d && e.getBatchId() != null) {
                price = batchPrice.computeIfAbsent(e.getBatchId(),
                        bid -> batchRepo.findById(bid).map(MaterialBatch::getUnitPrice).orElse(0d));
            }
            returnedAmount += e.getUnusedQty() * price;
        }

        double consumed = issuedAmount - returnedAmount;

        double crewWaste = 0d, supplierWaste = 0d, otherWaste = 0d;
        for (WasteRecord w : wasteRepo.findByCrewBetween(crewId, from, to)) {
            double amt = w.getQty() * nz(w.getUnitPrice());
            switch (String.valueOf(w.getResponsibility())) {
                case "CREW" -> crewWaste += amt;
                case "SUPPLIER" -> supplierWaste += amt;
                default -> otherWaste += amt;
            }
        }
        crewWaste += damagedAmount;

        double claim = visaRepo.findByCrewBetween(crewId, from, to).stream()
                .filter(v -> !"DRAFT".equals(v.getStatus()))
                .mapToDouble(v -> nz(v.getClaimAmount())).sum();

        double variance = consumed - budgetAmount;
        double deduction = crewWaste + Math.max(0d, variance);

        s.setBudgetAmount(r2(budgetAmount));
        s.setIssuedAmount(r2(issuedAmount));
        s.setReturnedAmount(r2(returnedAmount));
        s.setConsumedAmount(r2(consumed));
        s.setCrewWasteAmount(r2(crewWaste));
        s.setSupplierWasteAmount(r2(supplierWaste));
        s.setOtherWasteAmount(r2(otherWaste));
        s.setVarianceAmount(r2(variance));
        s.setDeductionAmount(r2(deduction));
        s.setClaimAmount(r2(claim));
        s.setStatus("DRAFT");
        s.setGeneratedBy(SecurityUtil.currentName());
        s.setCreatedTime(LocalDateTime.now());
        return settlementRepo.save(s);
    }

    /** 一键生成某月所有班组的核算单 */
    @Transactional
    public List<Settlement> generateAll(String period) {
        YearMonth.parse(period);
        List<Settlement> out = new ArrayList<>();
        for (Crew c : crewRepo.findAll()) {
            out.add(generate(period, c.getId()));
        }
        return out;
    }

    @Transactional
    public Settlement confirm(Long id) {
        Settlement s = settlementRepo.findById(id).orElseThrow(() -> new BusinessException("核算单不存在"));
        if (!"DRAFT".equals(s.getStatus())) {
            throw new BusinessException("仅草稿核算单可确认");
        }
        s.setStatus("CONFIRMED");
        s.setConfirmedBy(SecurityUtil.currentName());
        s.setConfirmTime(LocalDateTime.now());
        return settlementRepo.save(s);
    }

    public List<Settlement> list(String period) {
        return period == null ? settlementRepo.findAllByOrderByPeriodDescIdAsc()
                : settlementRepo.findByPeriodOrderByIdAsc(period);
    }

    public Settlement detail(Long id) {
        return settlementRepo.findById(id).orElseThrow(() -> new BusinessException("核算单不存在"));
    }

    private static double nz(Double v) {
        return v == null ? 0d : v;
    }

    private static double r2(double v) {
        return Math.round(v * 100d) / 100d;
    }
}
