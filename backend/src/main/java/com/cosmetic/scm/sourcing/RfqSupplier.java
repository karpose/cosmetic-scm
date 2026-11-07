package com.cosmetic.scm.sourcing;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 被邀请报价的供应商。 */
@Entity
@Table(name = "rfq_supplier")
@Getter
@Setter
@NoArgsConstructor
public class RfqSupplier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long rfqId;

    private Long supplierId;
}
