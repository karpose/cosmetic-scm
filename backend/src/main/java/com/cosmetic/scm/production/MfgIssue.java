package com.cosmetic.scm.production;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 生产发料明细：哪张生产单用了哪个物料批次多少。 */
@Entity
@Table(name = "mfg_issue")
@Getter
@Setter
@NoArgsConstructor
public class MfgIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long moId;

    private Long batchId;

    private Long warehouseId;

    private Long itemId;

    private BigDecimal qty;
}
