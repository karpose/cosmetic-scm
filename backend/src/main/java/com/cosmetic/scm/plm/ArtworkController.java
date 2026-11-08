package com.cosmetic.scm.plm;

import java.util.List;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 内部岗位都能看。提交给主数据和质量，批准和退回只给 QSO。 */
@RestController
@RequestMapping("/api/artworks")
@RequiredArgsConstructor
public class ArtworkController {

    private final ArtworkService artworks;

    @GetMapping
    public ArtworkService.Overview list() {
        return artworks.list();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('MASTER_DATA','QA','QSO')")
    public ArtworkService.ArtworkView create(@Valid @RequestBody ArtworkService.CreateReq req) {
        return artworks.create(req);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('QSO')")
    public ArtworkService.ArtworkView approve(@PathVariable Long id, @Valid @RequestBody ArtworkService.ApproveReq req) {
        return artworks.approve(id, req);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('QSO')")
    public ArtworkService.ArtworkView reject(@PathVariable Long id, @Valid @RequestBody ArtworkService.NoteReq req) {
        return artworks.reject(id, req);
    }
}
