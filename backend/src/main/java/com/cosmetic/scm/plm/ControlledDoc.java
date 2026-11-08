package com.cosmetic.scm.plm;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.DocCategory;
import com.cosmetic.scm.domain.DocStatus;

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

/** 一份受控文件。内容在修订版本的附件里，现行版本是已签批、已到生效日期的最新一版。 */
@Entity
@Table(name = "controlled_doc")
@Getter
@Setter
@NoArgsConstructor
public class ControlledDoc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String docCode;

    private String title;

    @Enumerated(EnumType.STRING)
    private DocCategory category;

    private String ownerDept;

    private Integer reviewMonths;

    @Enumerated(EnumType.STRING)
    private DocStatus status;

    private LocalDate lastReviewedOn;

    private String obsoleteReason;

    /** 需要确认已读的岗位，逗号分隔的 Role 名称。为空表示不需要确认。 */
    private String trainRoles;

    private String createdBy;

    private LocalDateTime createdAt;
}
