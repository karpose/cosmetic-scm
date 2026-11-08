package com.cosmetic.scm.plm;

import java.time.LocalDateTime;

import com.cosmetic.scm.domain.ArtworkStatus;

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

/** 一个包材的一版标签设计稿。设计稿文件在附件里。 */
@Entity
@Table(name = "label_artwork")
@Getter
@Setter
@NoArgsConstructor
public class LabelArtwork {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String artworkNo;

    private Long pkItemId;

    private Long fgItemId;

    /** 提交时成品的生效配方版本。配方变了，成分表可能要改，要重新评审。 */
    private Long formulaVersionId;

    private Integer versionNo;

    @Enumerated(EnumType.STRING)
    private ArtworkStatus status;

    private String changeNote;

    /** 批准时勾选的评审项，逗号分隔。 */
    private String checklist;

    private String rejectNote;

    private String createdBy;

    private LocalDateTime createdAt;

    private String approvedBy;

    private LocalDateTime approvedAt;
}
