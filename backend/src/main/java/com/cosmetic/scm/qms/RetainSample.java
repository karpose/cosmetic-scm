package com.cosmetic.scm.qms;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.RetainStatus;

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

/** 留样台账。上市放行时自动入账，保存期满后才能销毁。 */
@Entity
@Table(name = "retain_sample")
@Getter
@Setter
@NoArgsConstructor
public class RetainSample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long batchId;

    private Integer qty;

    private Integer remainingQty;

    private String location;

    /** 留样地点（V58 迁移起）；以前的留样为空，由质量部门指定。 */
    private Long siteId;

    private LocalDate retainUntil;

    @Enumerated(EnumType.STRING)
    private RetainStatus status;

    private String createdBy;

    private LocalDateTime createdAt;
}
