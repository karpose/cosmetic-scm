package com.cosmetic.scm.sales;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 出库批次：哪个批次发给了哪张出库单，召回时据此找到客户。 */
@Entity
@Table(name = "so_allocation")
@Getter
@Setter
@NoArgsConstructor
public class SoAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long soId;

    private Long itemId;

    private Long batchId;

    private Long warehouseId;

    private BigDecimal qty;
}
