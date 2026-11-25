package com.cosmetic.scm.oemcontract;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 合同覆盖的成品。 */
@Entity
@Table(name = "oem_contract_product")
@Getter
@Setter
@NoArgsConstructor
public class OemContractProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long contractId;

    private Long fgItemId;

    public OemContractProduct(Long contractId, Long fgItemId) {
        this.contractId = contractId;
        this.fgItemId = fgItemId;
    }
}
