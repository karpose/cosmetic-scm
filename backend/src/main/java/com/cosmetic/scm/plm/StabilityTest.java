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

/** 检测项目。有上下限的由系统判定，没有的人工判定。 */
@Entity
@Table(name = "stability_test")
@Getter
@Setter
@NoArgsConstructor
public class StabilityTest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long studyId;

    private Integer lineNo;

    private String name;

    private String unit;

    private BigDecimal minValue;

    private BigDecimal maxValue;

    private String criteria;
}
