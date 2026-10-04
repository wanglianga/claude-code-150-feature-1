package com.site.material.controller;

import com.site.material.domain.Requisition;
import com.site.material.dto.Requests.*;
import com.site.material.service.RequisitionService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/requisitions")
public class RequisitionController {

    private final RequisitionService service;

    public RequisitionController(RequisitionService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String status,
                                          @RequestParam(required = false) Long crewId) {
        return service.list(status, crewId);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        return service.detail(id);
    }

    /** 发料前核对：施工进度口径的预算量 / 已领量 / 现场库存 / 可用批次 */
    @GetMapping("/{id}/precheck")
    public Map<String, Object> precheck(@PathVariable Long id) {
        return service.preIssueCheck(id);
    }

    /** 同班组历史"规格领错"记录：再次申请相同材料时前端弹提示 */
    @GetMapping("/spec-wrong-alerts")
    public List<Map<String, Object>> specWrongAlerts(@RequestParam Long crewId,
                                                     @RequestParam(required = false) Long materialId) {
        return service.specWrongAlerts(crewId, materialId);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('FOREMAN','KEEPER','PM')")
    public Requisition create(@Valid @RequestBody RequisitionRequest req) {
        return service.create(req);
    }

    /** 仓管发料（指定批次+实发数量），班组长同步确认领用 */
    @PutMapping("/{id}/issue")
    @PreAuthorize("hasAnyRole('KEEPER','PM')")
    public Map<String, Object> issue(@PathVariable Long id, @Valid @RequestBody IssueRequest req) {
        return service.issue(id, req);
    }

    @PutMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('KEEPER','PM')")
    public Object reject(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        return service.reject(id, body == null ? null : body.get("remark"));
    }
}
