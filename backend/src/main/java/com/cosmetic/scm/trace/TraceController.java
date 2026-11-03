package com.cosmetic.scm.trace;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.trace.TraceService.TraceView;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trace")
@RequiredArgsConstructor
public class TraceController {

    private final TraceService trace;

    @GetMapping("/{batchNo}")
    public TraceView trace(@PathVariable String batchNo) {
        return trace.trace(batchNo);
    }
}
