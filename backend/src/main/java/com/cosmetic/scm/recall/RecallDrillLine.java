package com.cosmetic.scm.recall;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.DrillResult;
import com.cosmetic.scm.domain.DrillTarget;

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

/** 召回演练要联系的一个对象：收货客户、受托生产企业或仓库。数量和联系人是发起时的快照。 */
@Entity
@Table(name = "recall_drill_line")
@Getter
@Setter
@NoArgsConstructor
public class RecallDrillLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long drillId;

    private Integer seq;

    @Enumerated(EnumType.STRING)
    private DrillTarget target;

    private Long partnerId;

    private Long warehouseId;

    private BigDecimal expectedQty;

    private String contact;

    @Enumerated(EnumType.STRING)
    private DrillResult result;

    private BigDecimal confirmedQty;

    private LocalDateTime contactedAt;

    private String recordedBy;

    private String note;
}
