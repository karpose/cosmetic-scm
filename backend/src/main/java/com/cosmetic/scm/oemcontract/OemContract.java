package com.cosmetic.scm.oemcontract;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.ContractStatus;
import com.cosmetic.scm.domain.ContractSupply;

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

/** 和一家受托生产企业签的委托生产合同（含质量协议）。覆盖的成品在 oem_contract_product，签字扫描件在附件里。 */
@Entity
@Table(name = "oem_contract")
@Getter
@Setter
@NoArgsConstructor
public class OemContract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 系统编号（HT-日期-流水）。 */
    private String contractNo;

    /** 双方签署文本上的合同编号。 */
    private String extNo;

    private Long factoryId;

    private String title;

    private LocalDate signedOn;

    private LocalDate validFrom;

    private LocalDate validTo;

    @Enumerated(EnumType.STRING)
    private ContractSupply rmSupply;

    @Enumerated(EnumType.STRING)
    private ContractSupply pkSupply;

    private String otherTerms;

    /** 签批时确认的约定事项，逗号分隔。 */
    private String clauses;

    @Enumerated(EnumType.STRING)
    private ContractStatus status;

    private String rejectNote;

    private String createdBy;

    private LocalDateTime createdAt;

    private String submittedBy;

    private LocalDateTime submittedAt;

    private String approvedBy;

    private LocalDateTime approvedAt;

    private String terminatedBy;

    private LocalDateTime terminatedAt;

    /** 终止或作废的原因。 */
    private String endReason;
}
