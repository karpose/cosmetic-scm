package com.cosmetic.scm.purchase;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.PoStatus;

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
@Table(name = "purchase_order")
@Getter
@Setter
@NoArgsConstructor
public class PurchaseOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String poNo;

    private Long supplierId;

    private LocalDate orderDate;

    @Enumerated(EnumType.STRING)
    private PoStatus status;

    private String createdBy;

    private LocalDateTime createdAt;

    private String closeReason;

    private String closedBy;

    private LocalDateTime closedAt;
}
