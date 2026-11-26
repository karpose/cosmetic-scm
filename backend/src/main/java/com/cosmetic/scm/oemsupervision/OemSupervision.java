package com.cosmetic.scm.oemsupervision;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.SupervisionMethod;
import com.cosmetic.scm.domain.SupervisionStatus;

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

/** 一次对受托生产企业生产活动的监督（驻厂、现场检查、调阅记录、远程视频）。发现的问题在 oem_supervision_finding，照片和检查表在附件里。 */
@Entity
@Table(name = "oem_supervision")
@Getter
@Setter
@NoArgsConstructor
public class OemSupervision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** JD-日期-流水。 */
    private String supervisionNo;

    private Long factoryId;

    /** 针对某张委托单的监督；定期现场检查可以为空。 */
    private Long moId;

    @Enumerated(EnumType.STRING)
    private SupervisionMethod method;

    private LocalDate supervisedOn;

    /** 覆盖的环节，SupervisionScope 的代码，逗号分隔。 */
    private String scopes;

    private String participants;

    private String content;

    @Enumerated(EnumType.STRING)
    private SupervisionStatus status;

    private String rejectNote;

    private String reviewNote;

    private String cancelReason;

    private String createdBy;

    private LocalDateTime createdAt;

    private String submittedBy;

    private LocalDateTime submittedAt;

    private String reviewedBy;

    private LocalDateTime reviewedAt;
}
