package com.cosmetic.scm.purchase;

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
@Table(name = "po_line")
@Getter
@Setter
@NoArgsConstructor
public class PoLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long poId;

    private int lineNo;

    private Long itemId;

    private BigDecimal qty;

    private BigDecimal receivedQty;

    /** 含税单价，选填：询价定标带入，或采购录入。对账按它算。 */
    private BigDecimal unitPrice;

    /** 税率（%）。 */
    private BigDecimal taxRate;

    public BigDecimal remaining() {
        return qty.subtract(receivedQty).max(BigDecimal.ZERO);
    }
}
