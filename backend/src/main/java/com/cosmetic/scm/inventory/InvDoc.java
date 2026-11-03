package com.cosmetic.scm.inventory;

import java.time.LocalDateTime;

import com.cosmetic.scm.domain.InvDocType;

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

/** 库存作业单：仓间调拨、盘点调整、报废出库。 */
@Entity
@Table(name = "inv_doc")
@Getter
@Setter
@NoArgsConstructor
public class InvDoc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String docNo;

    @Enumerated(EnumType.STRING)
    private InvDocType docType;

    private Long fromWhId;

    private Long toWhId;

    private String reason;

    private String signedByName;

    private String createdBy;

    private LocalDateTime createdAt;
}
