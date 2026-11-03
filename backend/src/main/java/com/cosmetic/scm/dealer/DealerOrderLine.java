package com.cosmetic.scm.dealer;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 经销商订货明细。 */
@Entity
@Table(name = "dealer_order_line")
@Getter
@Setter
@NoArgsConstructor
public class DealerOrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long orderId;

    private Long itemId;

    private BigDecimal qty;
}
