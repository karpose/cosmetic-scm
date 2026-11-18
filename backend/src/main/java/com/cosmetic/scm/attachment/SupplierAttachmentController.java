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
/** 供应商门户的附件：只碰本单位的采购单和档案，只看本单位账号传的文件。 */
@RestController
@RequestMapping("/api/portal/supplier")
@RequiredArgsConstructor
public class SupplierAttachmentController {

    private final AttachmentService attachments;

    @GetMapping("/orders/{id}/attachments")
    public List<AttachmentView> orderFiles(@PathVariable Long id) {
        return attachments.listForSupplierOrder(id);
    }

    @PostMapping(value = "/orders/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AttachmentView uploadOrderFile(@PathVariable Long id, @RequestParam(required = false) String note,
                                          @RequestPart("file") MultipartFile file) {
        return attachments.uploadForSupplierOrder(id, file, note);
    }

    @GetMapping("/profile/attachments")
    public List<AttachmentView> profileFiles() {
        return attachments.listForSupplierProfile();
    }

    @PostMapping(value = "/profile/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AttachmentView uploadProfileFile(@RequestParam(required = false) String note, @RequestPart("file") MultipartFile file) {
        return attachments.uploadForSupplierProfile(file, note);
    }

    @GetMapping("/statements/{id}/attachments")
    public List<AttachmentView> statementFiles(@PathVariable Long id) {
        return attachments.listForSupplierStatement(id);
    }

    @PostMapping(value = "/statements/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AttachmentView uploadStatementFile(@PathVariable Long id, @RequestParam(required = false) String note, @RequestPart("file") MultipartFile file) {
        return attachments.uploadForSupplierStatement(id, file, note);
    }

    @GetMapping("/shipment-lines/{id}/attachments")
    public List<AttachmentView> shipmentLineFiles(@PathVariable Long id) {
        return attachments.listForSupplierShipmentLine(id);
    }

    @PostMapping(value = "/shipment-lines/{id}/coa", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AttachmentView uploadShipmentLineCoa(@PathVariable Long id,
                                                @RequestParam(required = false) String coaNo,
                                                @RequestParam(required = false) String note,
                                                @RequestPart("file") MultipartFile file) {
        return attachments.uploadForSupplierShipmentLine(id, file, coaNo, note);
    }

    @GetMapping("/attachments/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        return AttachmentController.asFile(attachments.downloadForSupplier(id));
    }
}
