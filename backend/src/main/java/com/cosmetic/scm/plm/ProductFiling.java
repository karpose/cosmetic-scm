package com.cosmetic.scm.plm;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.FilingStatus;
import com.cosmetic.scm.domain.FilingType;

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

/** 产品备案 / 注册记录。原料变更的配方版本必须有对应的已完成记录才能生效。 */
@Entity
@Table(name = "product_filing")
@Getter
@Setter
@NoArgsConstructor
public class ProductFiling {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long fgItemId;

    private Long formulaVersionId;

    @Enumerated(EnumType.STRING)
    private FilingType filingType;

    private String filingNo;

    @Enumerated(EnumType.STRING)
    private FilingStatus status;

    private LocalDate submittedDate;

    private LocalDate filedDate;

    private LocalDate expiryDate;

    private String note;

    private String createdBy;

    private LocalDateTime createdAt;
}
