package com.site.material.controller;

import com.site.material.domain.SpecExchange;
import com.site.material.dto.Requests.SpecExchangeConfirmRequest;
import com.site.material.dto.Requests.SpecExchangeRequest;
import com.site.material.service.SpecExchangeService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 规格领错退换料：班组长发起退换（未使用/已切割数量+现场照片），仓管确认并判定责任。
 */
@RestController
@RequestMapping("/api/spec-exchanges")
public class SpecExchangeController {

    private final SpecExchangeService service;

    public SpecExchangeController(SpecExchangeService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String status,
                                          @RequestParam(required = false) Long crewId) {
        return service.list(status, crewId);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('FOREMAN','KEEPER','PM')")
    public SpecExchange create(@Valid @RequestBody SpecExchangeRequest req) {
        return service.create(req);
    }

    /** 仓管确认：责任方 KEEPER 仓管发错（班组不扣款）/ CREW 班组申请错误（成本归班组） */
    @PutMapping("/{id}/confirm")
    @PreAuthorize("hasAnyRole('KEEPER','PM')")
    public Map<String, Object> confirm(@PathVariable Long id,
                                       @Valid @RequestBody SpecExchangeConfirmRequest req) {
        return service.view(service.confirm(id, req));
    }

    @PutMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('KEEPER','PM')")
    public SpecExchange reject(@PathVariable Long id) {
        return service.reject(id);
    }

    /** 同班组再次申请相同材料时查询“曾经领错规格”预警 */
    @GetMapping("/warnings")
    public List<Map<String, Object>> warnings(@RequestParam Long crewId, @RequestParam Long materialId) {
        return service.warnings(crewId, materialId);
    }
}
