package com.cosmetic.scm.storage;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 一次温湿度超限，对应一份偏差。最高最低值只按这次超限记录里超出范围的那一项算。 */
@Entity
@Table(name = "env_excursion")
@Getter
@Setter
@NoArgsConstructor
public class EnvExcursion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String excursionNo;

    private Long areaId;

    private LocalDateTime startedAt;

    private LocalDateTime lastAt;

    private int readingCount;

    private BigDecimal tempLow;

    private BigDecimal tempHigh;

    private BigDecimal rhLow;

    private BigDecimal rhHigh;

    private Long deviationId;

    private boolean voided;

    private String createdBy;

    private LocalDateTime createdAt;
}
