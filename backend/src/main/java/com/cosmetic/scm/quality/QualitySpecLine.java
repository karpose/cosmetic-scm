package com.cosmetic.scm.quality;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 标准里的一个检测项目。有上下限的由系统判定。 */
@Entity
@Table(name = "quality_spec_line")
@Getter
@Setter
@NoArgsConstructor
public class QualitySpecLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long specId;

    private Integer lineNo;

    private String name;

    private String method;

    private String unit;

    private BigDecimal minValue;

    private BigDecimal maxValue;

    private String criteria;

    /** 可以不自检、采用供方（供应商或受托厂）的检验报告。 */
    private boolean coaAllowed;
}
