package com.cosmetic.scm.inventory;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 某批次在某仓库的结存。只通过 InventoryService 修改。 */
@Entity
@Table(name = "stock")
@Getter
@Setter
@NoArgsConstructor
public class Stock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long batchId;

    private Long itemId;

    private Long warehouseId;

    private BigDecimal qty;

    @Version
    @Column(name = "row_version")
    private Long rowVersion;
}
