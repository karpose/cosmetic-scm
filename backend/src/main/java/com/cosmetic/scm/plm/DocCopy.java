package com.cosmetic.scm.plm;

import java.time.LocalDateTime;

import com.cosmetic.scm.domain.DocCopyStatus;

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

/** 发出去的一份纸质受控副本。按版本编号：第几版的第几份。 */
@Entity
@Table(name = "doc_copy")
@Getter
@Setter
@NoArgsConstructor
public class DocCopy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long docId;

    private Long revisionId;

    private Integer copyNo;

    /** 持有人、部门或存放地点。 */
    private String holder;

    @Enumerated(EnumType.STRING)
    private DocCopyStatus status;

    private String issuedBy;

    private LocalDateTime issuedAt;

    /** 回收或登记遗失的人。 */
    private String closedBy;

    private LocalDateTime closedAt;

    private String closeNote;
}
