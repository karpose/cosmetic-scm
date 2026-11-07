package com.cosmetic.scm.sourcing;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 询价的物料行。 */
@Entity
@Table(name = "rfq_line")
@Getter
@Setter
@NoArgsConstructor
public class RfqLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long rfqId;

    private Long itemId;

    private BigDecimal qty;
}
