package com.cosmetic.scm.qms;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 质量安全负责人：内部岗位都能看；任职登记、生效、离任给管理员；授权、撤销、监督记录给质量安全负责人（服务层再核对是不是现任）。 */
@RestController
@RequestMapping("/api/qso")
@RequiredArgsConstructor
public class QsoController {

    private final QsoService qso;

    @GetMapping
    public QsoService.Overview overview() {
        return qso.overview();
    }

    @PostMapping("/appointments")
    @PreAuthorize("hasRole('ADMIN')")
    public QsoService.AppointmentView appoint(@Valid @RequestBody QsoService.AppointReq req) {
        return qso.appoint(req);
    }

    @PostMapping("/appointments/{id}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public QsoService.AppointmentView activate(@PathVariable Long id, @Valid @RequestBody QsoService.PasswordReq req) {
        return qso.activate(id, req);
    }

    @PostMapping("/appointments/{id}/end")
    @PreAuthorize("hasRole('ADMIN')")
    public QsoService.AppointmentView end(@PathVariable Long id, @Valid @RequestBody QsoService.NoteReq req) {
        return qso.endAppointment(id, req);
    }

    @PostMapping("/delegations")
    @PreAuthorize("hasRole('QSO')")
    public QsoService.DelegationView delegate(@Valid @RequestBody QsoService.DelegateReq req) {
        return qso.delegate(req);
    }

    @PostMapping("/delegations/{id}/revoke")
    @PreAuthorize("hasRole('QSO')")
    public QsoService.DelegationView revoke(@PathVariable Long id, @Valid @RequestBody QsoService.NoteReq req) {
        return qso.revoke(id, req);
    }

    @PostMapping("/delegations/{id}/reviews")
    @PreAuthorize("hasRole('QSO')")
    public QsoService.DelegationView review(@PathVariable Long id, @Valid @RequestBody QsoService.ReviewReq req) {
        return qso.review(id, req);
    }
}
