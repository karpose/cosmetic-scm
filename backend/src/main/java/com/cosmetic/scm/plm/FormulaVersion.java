package com.cosmetic.scm.plm;

import java.time.LocalDateTime;

import com.cosmetic.scm.domain.FormulaStatus;

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

/** 配方版本。草稿 → 待签批 → 生效；生效时内容写入 bom / bom_line，上一版转为已停用。 */
@Entity
@Table(name = "formula_version")
@Getter
@Setter
@NoArgsConstructor
public class FormulaVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long fgItemId;

    private Integer versionNo;

    @Enumerated(EnumType.STRING)
    private FormulaStatus status;

    private String changeNote;

    private String rejectNote;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime submittedAt;

    private String approvedBy;

    private LocalDateTime approvedAt;
}
