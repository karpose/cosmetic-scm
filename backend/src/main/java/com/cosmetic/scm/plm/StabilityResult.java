package com.cosmetic.scm.plm;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 某个时间点某个项目的检测结果。 */
@Entity
@Table(name = "stability_result")
@Getter
@Setter
@NoArgsConstructor
public class StabilityResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long pullId;

    private Long testId;

    private BigDecimal valueNum;

    private String valueText;

    private boolean passed;
}
