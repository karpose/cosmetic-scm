package com.cosmetic.scm.transport;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.TransportDirection;
import com.cosmetic.scm.domain.TransportMode;

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

/** 一次运输。只增不改，录错的作废（保留）。条件是登记时这些产品的条件（取最严的）。 */
@Entity
@Table(name = "transport_record")
@Getter
@Setter
@NoArgsConstructor
public class TransportRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String recordNo;

    @Enumerated(EnumType.STRING)
    private TransportDirection direction;

    @Enumerated(EnumType.STRING)
    private TransportMode mode;

    private String carrier;

    private String waybillNo;

    private LocalDateTime shippedAt;

    private LocalDateTime deliveredAt;

    private BigDecimal reqTempMin;

    private BigDecimal reqTempMax;

    private boolean reqMonitor;

    private String reqText;

    private boolean tempMonitored;

    private BigDecimal tempLow;

    private BigDecimal tempHigh;

    private String device;

    private boolean damaged;

    private String damageNote;

    private boolean conforming;

    private String problem;

    private Long deviationId;

    private String note;

    private boolean voided;

    private String voidReason;

    private String voidedBy;

    private LocalDateTime voidedAt;

    private String createdBy;

    private LocalDateTime createdAt;
}
