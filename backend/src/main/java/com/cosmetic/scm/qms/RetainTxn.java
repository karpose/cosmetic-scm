package com.cosmetic.scm.qms;

import java.time.LocalDateTime;

import com.cosmetic.scm.domain.RetainTxnType;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 留样领用和销毁记录。 */
@Entity
@Table(name = "retain_txn")
@Getter
@Setter
@NoArgsConstructor
public class RetainTxn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long sampleId;

    @Enumerated(EnumType.STRING)
    private RetainTxnType txnType;

    private Integer qty;

    private String reason;

    private String doneBy;

    private LocalDateTime doneAt;
}
