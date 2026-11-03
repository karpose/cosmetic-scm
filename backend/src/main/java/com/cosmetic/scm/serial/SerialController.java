package com.cosmetic.scm.serial;

import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.serial.SerialService.BatchCodes;
import com.cosmetic.scm.serial.SerialService.CodeView;
import com.cosmetic.scm.serial.SerialService.GenerateReq;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 一物一码（系统内）：查看各批次生码情况、生成、导出给印刷厂、查某个码的扫码记录。 */
@RestController
@RequestMapping("/api/serials")
@RequiredArgsConstructor
public class SerialController {

    private final SerialService serials;

    @GetMapping("/batches")
    public List<BatchCodes> batches() {
        return serials.batches();
    }

    @PostMapping("/generate")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCTION','QA','QSO')")
    public BatchCodes generate(@Valid @RequestBody GenerateReq req) {
        return serials.generate(req);
    }

    @GetMapping("/batches/{batchId}/export")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCTION','QA','QSO')")
    public ResponseEntity<byte[]> export(@PathVariable Long batchId) {
        byte[] body = serials.exportCsv(batchId).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("serial-codes-" + batchId + ".csv", StandardCharsets.UTF_8).build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    @GetMapping("/codes/{code}")
    public CodeView lookup(@PathVariable String code) {
        return serials.lookup(code);
    }
}
