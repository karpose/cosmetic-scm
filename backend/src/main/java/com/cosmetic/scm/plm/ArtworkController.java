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

/** 内部岗位都能看。提交给主数据和质量，批准和退回只给 QSO；打样登记给主数据、采购和质量，签样给质量，作废签样只给 QSO。 */
@RestController
@RequestMapping("/api/artworks")
@RequiredArgsConstructor
public class ArtworkController {

    private final ArtworkService artworks;
    private final ArtworkProofService proofs;

    @GetMapping
    public ArtworkService.Overview list() {
        return artworks.list();
    }

    /** 包材现行的设计稿，没有时返回 204。放行包材时核对用。 */
    @GetMapping("/current/{pkItemId}")
    public org.springframework.http.ResponseEntity<ArtworkService.ArtworkView> current(@PathVariable Long pkItemId) {
        return artworks.current(pkItemId).map(org.springframework.http.ResponseEntity::ok).orElse(org.springframework.http.ResponseEntity.noContent().build());
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

    /** 设计稿详情：打样记录和按这一版放行的包材批次。 */
    @GetMapping("/{id}")
    public ArtworkProofService.ArtworkDetail detail(@PathVariable Long id) {
        return proofs.detail(id);
    }

    /** 放行包材批次前的设计稿和签样，没有现行设计稿时返回 204。 */
    @GetMapping("/release-check/{batchId}")
    public org.springframework.http.ResponseEntity<ArtworkProofService.ReleaseCheck> releaseCheck(@PathVariable Long batchId) {
        return proofs.releaseCheck(batchId).map(org.springframework.http.ResponseEntity::ok).orElse(org.springframework.http.ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/proofs")
    @PreAuthorize("hasAnyRole('MASTER_DATA','PURCHASE','QA','QSO')")
    public ArtworkProofService.ProofView createProof(@PathVariable Long id, @Valid @RequestBody ArtworkProofService.CreateReq req) {
        return proofs.create(id, req);
    }

    @PostMapping("/proofs/{proofId}/approve")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public ArtworkProofService.ProofView approveProof(@PathVariable Long proofId, @Valid @RequestBody ArtworkProofService.ApproveReq req) {
        return proofs.approve(proofId, req);
    }

    @PostMapping("/proofs/{proofId}/reject")
    @PreAuthorize("hasAnyRole('QA','QSO')")
    public ArtworkProofService.ProofView rejectProof(@PathVariable Long proofId, @Valid @RequestBody ArtworkProofService.NoteReq req) {
        return proofs.reject(proofId, req);
    }

    @PostMapping("/proofs/{proofId}/void")
    @PreAuthorize("hasRole('QSO')")
    public ArtworkProofService.ProofView voidProof(@PathVariable Long proofId, @Valid @RequestBody ArtworkProofService.NoteReq req) {
        return proofs.voidProof(proofId, req);
    }
}
