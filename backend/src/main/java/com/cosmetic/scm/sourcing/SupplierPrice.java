package com.cosmetic.scm.sourcing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 供应商协议价 / 价目表：按供应商 + 物料维护含税单价、税率与有效期。 */
@Entity
@Table(name = "supplier_price")
@Getter
@Setter
@NoArgsConstructor
public class SupplierPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long supplierId;

    private Long itemId;

    private BigDecimal unitPrice;

    private BigDecimal taxRate = new BigDecimal("13.00");

    private LocalDate validFrom;

    private LocalDate validTo;

    private boolean active = true;

    private String note;

    private String createdBy;

    private LocalDateTime createdAt;

    private String updatedBy;

    private LocalDateTime updatedAt;

    public boolean isEffectiveOn(LocalDate date) {
        if (!active || date == null || validFrom == null) {
            return false;
        }
        return !validFrom.isAfter(date) && (validTo == null || !validTo.isBefore(date));
    }
}
