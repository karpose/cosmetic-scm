package com.cosmetic.scm.storage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.StorageAreaKind;
import com.cosmetic.scm.domain.rules.StorageCondition;

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

/** 贮存区域（仓库的某个区、留样室等）和它的温湿度范围。范围由质量部门设定，不内置。 */
@Entity
@Table(name = "storage_area")
@Getter
@Setter
@NoArgsConstructor
public class StorageArea {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String code;

    private String name;

    @Enumerated(EnumType.STRING)
    private StorageAreaKind kind;

    private Long warehouseId;

    private BigDecimal tempMin;

    private BigDecimal tempMax;

    private BigDecimal rhMin;

    private BigDecimal rhMax;

    private boolean dailyRequired;

    private LocalDate monitorFrom;

    private boolean active;

    private String note;

    private String createdBy;

    private LocalDateTime createdAt;

    private String updatedBy;

    private LocalDateTime updatedAt;

    public StorageCondition.Limits limits() {
        return new StorageCondition.Limits(tempMin, tempMax, rhMin, rhMax);
    }
}
