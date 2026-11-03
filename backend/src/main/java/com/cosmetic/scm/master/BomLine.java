package com.cosmetic.scm.master;

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

@Entity
@Table(name = "bom_line")
@Getter
@Setter
@NoArgsConstructor
public class BomLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long bomId;

    private int lineNo;

    private Long itemId;

    private BigDecimal qtyPer;

    @Enumerated(EnumType.STRING)
    private SupplyMode supplyMode;
}
