package com.cosmetic.scm.master;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.RegulatedCatalog;

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

/** 法规目录的一条。内容只由导入进来，系统不内置法规数据。 */
@Entity
@Table(name = "regulated_substance")
@Getter
@Setter
@NoArgsConstructor
public class RegulatedSubstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private RegulatedCatalog catalog;

    private String inciName;

    /** 小写、去首尾空格的 INCI，用来和物料对照。 */
    private String inciKey;

    private String cnName;

    /** 别名，分号分隔，只用于显示；对照用 regulated_alias 表。 */
    private String aliases;

    private String casNo;

    /** 限用浓度上限（%）。 */
    private BigDecimal maxPct;

    private String conditions;

    /** 来源文件和版本，必填：说不清出处的数据不能当法规用。 */
    private String source;

    private boolean active = true;

    private String updatedBy;

    private LocalDateTime updatedAt;
}
