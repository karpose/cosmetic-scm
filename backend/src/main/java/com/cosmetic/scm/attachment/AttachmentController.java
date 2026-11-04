package com.cosmetic.scm.attachment;

import java.nio.charset.StandardCharsets;
import java.util.List;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.attachment.AttachmentService.AttachmentView;
import com.cosmetic.scm.attachment.AttachmentService.Download;
import com.cosmetic.scm.domain.OwnerType;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 附件（系统内）：除只读账号外都可以上传；没有删除接口。 */
@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachments;

    @GetMapping
    public List<AttachmentView> list(@RequestParam OwnerType ownerType, @RequestParam Long ownerId) {
        return attachments.list(ownerType, ownerId);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','MASTER_DATA','PURCHASE','WAREHOUSE','PRODUCTION','QA','QSO','SALES')")
    public AttachmentView upload(@RequestParam OwnerType ownerType, @RequestParam Long ownerId,
                                 @RequestParam(required = false) String note, @RequestPart("file") MultipartFile file) {
        return attachments.upload(ownerType, ownerId, file, note);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        return asFile(attachments.download(id));
    }

    static ResponseEntity<byte[]> asFile(Download d) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.fileName(), StandardCharsets.UTF_8)
                        .build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(d.contentType()))
                .body(d.bytes());
    }
}
