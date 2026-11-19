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

@Entity
@Table(name = "so_line")
@Getter
@Setter
@NoArgsConstructor
public class SoLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long soId;

    private Long itemId;

    private BigDecimal qty;

    private BigDecimal unitPrice;

    private BigDecimal taxRate;
}
