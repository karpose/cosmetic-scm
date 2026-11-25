package com.cosmetic.scm.oemcontract;

import java.util.List;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 委托生产合同：内部岗位都能看（合同里没有金额）；登记、修改、提交、作废给管理员、采购、生产、质量检验、质量安全负责人；
 * 签批、退回只给质量安全负责人（服务层再核对不是登记人或提交人）；终止给管理员和质量安全负责人。
 */
@RestController
@RequestMapping("/api/oem-contracts")
@RequiredArgsConstructor
public class OemContractController {

    private static final String EDITORS = "hasAnyRole('ADMIN','PURCHASE','PRODUCTION','QA','QSO')";

    private final OemContractService service;

    @GetMapping
    public OemContractService.Overview overview() {
        return service.overview();
    }

    @GetMapping("/orders")
    public List<OemContractService.OrderCheck> orders() {
        return service.orderChecks();
    }

    @GetMapping("/check")
    public OemContractService.CheckView check(@RequestParam Long factoryId, @RequestParam Long fgItemId) {
        return service.check(factoryId, fgItemId);
    }

    @PostMapping
    @PreAuthorize(EDITORS)
    public OemContractService.ContractView create(@Valid @RequestBody OemContractService.SaveReq req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    @PreAuthorize(EDITORS)
    public OemContractService.ContractView update(@PathVariable Long id, @Valid @RequestBody OemContractService.SaveReq req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize(EDITORS)
    public OemContractService.ContractView submit(@PathVariable Long id) {
        return service.submit(id);
    }

    @PostMapping("/{id}/void")
    @PreAuthorize(EDITORS)
    public OemContractService.ContractView voidDraft(@PathVariable Long id, @Valid @RequestBody OemContractService.NoteReq req) {
        return service.voidDraft(id, req);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('QSO')")
    public OemContractService.ContractView approve(@PathVariable Long id, @Valid @RequestBody OemContractService.ApproveReq req) {
        return service.approve(id, req);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('QSO')")
    public OemContractService.ContractView reject(@PathVariable Long id, @Valid @RequestBody OemContractService.NoteReq req) {
        return service.reject(id, req);
    }

    @PostMapping("/{id}/terminate")
    @PreAuthorize("hasAnyRole('ADMIN','QSO')")
    public OemContractService.ContractView terminate(@PathVariable Long id, @Valid @RequestBody OemContractService.NoteReq req) {
        return service.terminate(id, req);
    }
}
