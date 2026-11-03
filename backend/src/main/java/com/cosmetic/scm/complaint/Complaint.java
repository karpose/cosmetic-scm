package com.cosmetic.scm.complaint;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.CaseStatus;
import com.cosmetic.scm.domain.ComplaintCategory;
import com.cosmetic.scm.domain.Severity;

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

/** 客户 / 消费者投诉。不良反应类投诉须在时限内完成监测上报后才能关闭。 */
@Entity
@Table(name = "complaint")
@Getter
@Setter
@NoArgsConstructor
public class Complaint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String complaintNo;

    private LocalDate receivedDate;

    private String channel;

    private Long customerId;

    private String consumer;

    private Long itemId;

    private Long batchId;

    @Enumerated(EnumType.STRING)
    private ComplaintCategory category;

    @Enumerated(EnumType.STRING)
    private Severity severity;

    private String description;

    @Enumerated(EnumType.STRING)
    private CaseStatus status;

    private String reportNo;

    private LocalDate reportedDate;

    private String conclusion;

    private String capa;

    private Long recallId;

    private String closedBy;

    private LocalDateTime closedAt;

    private String createdBy;

    private LocalDateTime createdAt;
}
