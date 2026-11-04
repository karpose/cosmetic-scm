package com.cosmetic.scm.report;

import java.nio.charset.StandardCharsets;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reports;

    @GetMapping("/{key}")
    public ResponseEntity<byte[]> export(@PathVariable String key,
                                         @RequestParam(required = false) java.time.LocalDate from,
                                         @RequestParam(required = false) java.time.LocalDate to) {
        ReportService.Export e = reports.export(key, from, to);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(e.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(e.content());
    }
}
