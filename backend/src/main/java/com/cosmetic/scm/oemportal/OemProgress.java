package com.cosmetic.scm.oemportal;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.OemStage;

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

/** 受托厂回传的进度记录，只增不改。 */
@Entity
@Table(name = "oem_progress")
@Getter
@Setter
@NoArgsConstructor
public class OemProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long moId;

    @Enumerated(EnumType.STRING)
    private OemStage stage;

    private LocalDate promiseDate;

    private String note;

    private String createdBy;

    private LocalDateTime createdAt;
}
