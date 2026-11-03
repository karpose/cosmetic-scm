package com.cosmetic.scm.salesreturn;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.ReturnDisposition;

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

/** 退货明细与处置结论。 */
@Entity
@Table(name = "sales_return_line")
@Getter
@Setter
@NoArgsConstructor
public class SalesReturnLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long returnId;

    private Long batchId;

    private Long itemId;

    private BigDecimal qty;

    @Enumerated(EnumType.STRING)
    private ReturnDisposition disposition;

    private Long targetWhId;

    private String dispositionNote;

    private String disposedByName;

    private LocalDateTime disposedAt;
}
