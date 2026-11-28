package com.cosmetic.scm.qms;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.RetainSiteKind;

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

/** 留样地点（GMP 第五十六条）：住所、主要经营场所，或所在地的其他经营场所（要报告药品监督管理部门）。 */
@Entity
@Table(name = "retain_site")
@Getter
@Setter
@NoArgsConstructor
public class RetainSite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String code;

    private String name;

    private String address;

    @Enumerated(EnumType.STRING)
    private RetainSiteKind kind;

    private boolean sameLocality;

    private LocalDate usedSince;

    private boolean active;

    /** 默认地点为 1，其余为空（唯一约束）。 */
    private Integer defaultKey;

    private String note;

    private LocalDate reportDate;

    private String reportAuthority;

    private String reportRef;

    private String reportNote;

    private String reportedBy;

    private LocalDateTime reportedAt;

    private String createdBy;

    private LocalDateTime createdAt;

    private String updatedBy;

    private LocalDateTime updatedAt;

    public boolean isDefaultSite() {
        return defaultKey != null;
    }
}
