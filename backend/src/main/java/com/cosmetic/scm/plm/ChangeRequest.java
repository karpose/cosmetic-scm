package com.cosmetic.scm.plm;

import java.time.LocalDateTime;

import com.cosmetic.scm.domain.ChangeStatus;
import com.cosmetic.scm.domain.ChangeType;
import com.cosmetic.scm.domain.RiskLevel;

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

/** 一项变更：申请 → 质量评估 → QSO 批准 → 执行行动项 → QSO 关闭。 */
@Entity
@Table(name = "change_request")
@Getter
@Setter
@NoArgsConstructor
public class ChangeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String changeNo;

    private String title;

    @Enumerated(EnumType.STRING)
    private ChangeType changeType;

    private String reason;

    private String description;

    private String scope;

    @Enumerated(EnumType.STRING)
    private ChangeStatus status;

    @Enumerated(EnumType.STRING)
    private RiskLevel risk;

    private String impact;

    private String assessedBy;

    private LocalDateTime assessedAt;

    private String approvedBy;

    private LocalDateTime approvedAt;

    private String rejectNote;

    private String closeNote;

    private String closedBy;

    private LocalDateTime closedAt;

    private String createdBy;

    private LocalDateTime createdAt;
}
