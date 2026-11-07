package com.cosmetic.scm.sourcing;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.RfqStatus;

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

/** 询价单。 */
@Entity
@Table(name = "rfq")
@Getter
@Setter
@NoArgsConstructor
public class Rfq {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String rfqNo;

    private String title;

    private LocalDate deadline;

    @Enumerated(EnumType.STRING)
    private RfqStatus status;

    private String closeReason;

    private String awardReason;

    private String awardedBy;

    private LocalDateTime awardedAt;

    private String createdBy;

    private LocalDateTime createdAt;
}
