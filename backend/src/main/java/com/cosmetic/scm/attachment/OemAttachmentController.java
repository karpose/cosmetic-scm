package com.cosmetic.scm.attachment;

import java.util.List;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.attachment.AttachmentService.AttachmentView;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 代工厂门户的附件：受托厂上传出厂检验报告、批生产记录等，只能看到本厂委托单上的附件。 */
@RestController
@RequestMapping("/api/portal/oem")
@RequiredArgsConstructor
public class OemAttachmentController {

    private final AttachmentService attachments;

    @GetMapping("/orders/{moId}/attachments")
    public List<AttachmentView> list(@PathVariable Long moId) {
        return attachments.listForOem(moId);
    }

    @PostMapping(value = "/orders/{moId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AttachmentView upload(@PathVariable Long moId, @RequestParam(required = false) String note,
                                 @RequestPart("file") MultipartFile file) {
        return attachments.uploadForOem(moId, file, note);
    }

    @GetMapping("/attachments/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        return AttachmentController.asFile(attachments.downloadForOem(id));
    }
}
