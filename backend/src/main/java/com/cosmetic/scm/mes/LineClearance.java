package com.cosmetic.scm.mes;

import java.time.LocalDateTime;

import com.cosmetic.scm.domain.ClearancePhase;

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

/** 清场确认。开工前没有清场不能开始工序，完工后没有清场不能入库。 */
@Entity
@Table(name = "line_clearance")
@Getter
@Setter
@NoArgsConstructor
public class LineClearance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long moId;

    @Enumerated(EnumType.STRING)
    private ClearancePhase phase;

    private String area;

    private String prevProduct;

    private String note;

    private String signedBy;

    private LocalDateTime signedAt;
}
