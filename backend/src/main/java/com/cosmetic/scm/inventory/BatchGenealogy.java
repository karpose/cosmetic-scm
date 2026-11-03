package com.cosmetic.scm.inventory;

import java.math.BigDecimal;

import com.cosmetic.scm.domain.SupplyMode;

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

/** 成品批次用了哪些物料批次。工厂代采的物料没有内部批次，只记外部批号。 */
@Entity
@Table(name = "batch_genealogy")
@Getter
@Setter
@NoArgsConstructor
public class BatchGenealogy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long childBatchId;

    private Long parentBatchId;

    private Long itemId;

    private BigDecimal qty;

    private String extBatchNo;

    @Enumerated(EnumType.STRING)
    private SupplyMode supplyMode;
}
