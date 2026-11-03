package com.cosmetic.scm.serial;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 一物一码：每件成品一个随机防伪溯源码。 */
@Entity
@Table(name = "serial_code")
@Getter
@Setter
@NoArgsConstructor
public class SerialCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String code;

    private Long batchId;

    private int seq;

    private String createdBy;

    private LocalDateTime createdAt;

    private int scanCount;

    private LocalDateTime firstScanAt;

    private LocalDateTime lastScanAt;
}
