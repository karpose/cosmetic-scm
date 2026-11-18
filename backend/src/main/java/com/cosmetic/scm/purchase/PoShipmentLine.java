package com.cosmetic.scm.purchase;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "po_shipment_line")
@Getter
@Setter
@NoArgsConstructor
public class PoShipmentLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long shipmentId;

    private Long poLineId;

    private BigDecimal qty;

    private String extBatchNo;

    private LocalDate mfgDate;

    private LocalDate expDate;

    private String coaNo;

    private Long coaAttachmentId;
}
