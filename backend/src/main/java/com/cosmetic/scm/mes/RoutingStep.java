package com.cosmetic.scm.mes;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 工艺路线模板的一道工序。修改只影响之后发料的工单。 */
@Entity
@Table(name = "routing_step")
@Getter
@Setter
@NoArgsConstructor
public class RoutingStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long fgItemId;

    private Integer stepNo;

    private String name;

    private String instruction;

    private boolean critical;

    private String ipcName;

    private BigDecimal ipcMin;

    private BigDecimal ipcMax;

    private String ipcUnit;
}
