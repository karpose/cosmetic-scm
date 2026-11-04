package com.cosmetic.scm.qms;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.CapaSource;
import com.cosmetic.scm.domain.CapaStatus;
import com.cosmetic.scm.domain.CapaType;

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

/** 纠正预防措施。负责人完成后由他人验证有效才算结束，验证无效退回继续。 */
@Entity
@Table(name = "capa")
@Getter
@Setter
@NoArgsConstructor
public class Capa {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String capaNo;

    @Enumerated(EnumType.STRING)
    private CapaSource sourceType;

    private String sourceNo;

    private Long deviationId;

    @Enumerated(EnumType.STRING)
    private CapaType actionType;

    private String description;

    private String owner;

    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    private CapaStatus status;

    private String doneNote;

    private String doneBy;

    private LocalDateTime doneAt;

    private String verifyNote;

    private String verifiedBy;

    private LocalDateTime verifiedAt;

    private String createdBy;

    private LocalDateTime createdAt;
}
