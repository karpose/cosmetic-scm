package com.cosmetic.scm.inventory;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 库存作业单明细。盘点行的 qty 是差异数量，可为负。 */
@Entity
@Table(name = "inv_doc_line")
@Getter
@Setter
@NoArgsConstructor
public class InvDocLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long docId;

    private Long batchId;

    private Long itemId;

    private BigDecimal bookQty;

    private BigDecimal qty;
}
