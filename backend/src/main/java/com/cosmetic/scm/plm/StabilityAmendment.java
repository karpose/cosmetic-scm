package com.cosmetic.scm.plm;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 稳定性试验方案的一次修改。登记时是第 0 版，每改一次加一版。 */
@Entity
@Table(name = "stability_amendment")
@Getter
@Setter
@NoArgsConstructor
public class StabilityAmendment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long studyId;

    private Integer revNo;

    private String reason;

    /** 改了什么，系统生成。 */
    private String summary;

    private String createdBy;

    private LocalDateTime createdAt;
}
