package com.cosmetic.scm.storage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.EnvSource;
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

/** 一条温湿度记录。只增不改，录错的作废（保留）。范围是记录时区域的范围。 */
@Entity
@Table(name = "env_record")
@Getter
@Setter
@NoArgsConstructor
public class EnvRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long areaId;

    private LocalDateTime recordedAt;

    private LocalDate recordedOn;

    private BigDecimal temp;

    private BigDecimal rh;

    private BigDecimal tempMin;

    private BigDecimal tempMax;

    private BigDecimal rhMin;

    private BigDecimal rhMax;

    private boolean inRange;

    @Enumerated(EnumType.STRING)
    private EnvSource source;

    private String device;

    private String note;

    private Long excursionId;

    private String dupKey;

    private boolean voided;

    private String voidReason;

    private String voidedBy;

    private LocalDateTime voidedAt;

    private String createdBy;

    private LocalDateTime createdAt;

    public StorageCondition.Limits limits() {
        return new StorageCondition.Limits(tempMin, tempMax, rhMin, rhMax);
    }
}
