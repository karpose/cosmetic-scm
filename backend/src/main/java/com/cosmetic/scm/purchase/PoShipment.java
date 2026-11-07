package com.cosmetic.scm.purchase;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.ShipmentStatus;

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

/** 供应商的送货通知。只是预告，真正入库仍由仓库收货确认。 */
@Entity
@Table(name = "po_shipment")
@Getter
@Setter
@NoArgsConstructor
public class PoShipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String shipmentNo;

    private Long poId;

    private LocalDate shipDate;

    @Enumerated(EnumType.STRING)
    private ShipmentStatus status;

    private String note;

    private String createdBy;

    private LocalDateTime createdAt;
}
