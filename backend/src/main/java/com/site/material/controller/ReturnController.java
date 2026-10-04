package com.site.material.controller;

import com.site.material.dto.Requests.ReturnConfirmRequest;
import com.site.material.dto.Requests.ReturnRequest;
import com.site.material.service.ReturnService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/returns")
public class ReturnController {

    private final ReturnService service;

    public ReturnController(ReturnService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String status,
                                          @RequestParam(required = false) Long crewId) {
        return service.list(status, crewId);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        return service.view(service.get(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('FOREMAN','KEEPER','PM')")
    public Map<String, Object> create(@Valid @RequestBody ReturnRequest req) {
        return service.view(service.create(req));
    }

    /** 仓管确认：规格领错退换必须在 body 中给出 faultType（WAREHOUSE 仓管发错 / CREW 班组申请错误） */
    @PutMapping("/{id}/confirm")
    @PreAuthorize("hasAnyRole('KEEPER','PM')")
    public Map<String, Object> confirm(@PathVariable Long id,
                                       @RequestBody(required = false) ReturnConfirmRequest req) {
        return service.view(service.confirm(id, req));
    }

    @PutMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('KEEPER','PM')")
    public Map<String, Object> reject(@PathVariable Long id) {
        return service.view(service.reject(id));
    }
}
