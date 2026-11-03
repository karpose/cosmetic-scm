package com.cosmetic.scm.inventory;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.TxnType;

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

@Entity
@Table(name = "stock_txn")
@Getter
@Setter
@NoArgsConstructor
public class StockTxn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long batchId;

    private Long warehouseId;

    private BigDecimal qtyDelta;

    @Enumerated(EnumType.STRING)
    private TxnType txnType;

    private String refNo;

    private String operator;

    private LocalDateTime createdAt;
}
