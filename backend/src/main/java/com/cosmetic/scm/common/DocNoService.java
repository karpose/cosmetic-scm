package com.cosmetic.scm.common;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单号与批号生成：前缀-yyMMdd-流水，如 PO-261102-01、A3-261102-01。
 * 必须在业务事务内调用，流水行被锁到事务结束，保证并发不重号。
 */
@Service
@RequiredArgsConstructor
public class DocNoService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyMMdd");

    private final DocSequenceRepository repository;

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(String prefix, LocalDate date) {
        String key = prefix + "-" + date.format(DAY);
        DocSequence seq = repository.lockByKey(key).orElseGet(() -> new DocSequence(key, 0));
        seq.setSeqValue(seq.getSeqValue() + 1);
        repository.save(seq);
        return key + "-" + String.format("%02d", seq.getSeqValue());
    }
}
