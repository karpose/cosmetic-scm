package com.cosmetic.scm.plm;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.CorrectionStatus;

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

/** 一个检测结果的更正申请。批准后结果改为新值，原值留在这里。 */
@Entity
@Table(name = "stability_correction")
@Getter
@Setter
@NoArgsConstructor
public class StabilityCorrection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long studyId;

    private Long pullId;

    private Long testId;

    private BigDecimal oldValue;

    private String oldText;

    private boolean oldPassed;

    private BigDecimal newValue;

    private String newText;

    private boolean newPassed;

    private String reason;

    @Enumerated(EnumType.STRING)
    private CorrectionStatus status;

    private String requestedBy;

    private LocalDateTime requestedAt;

    private String decidedBy;

    private LocalDateTime decidedAt;

    private String decideNote;
}
