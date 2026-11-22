package com.cosmetic.scm.production;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.MoStatus;
import com.cosmetic.scm.domain.MoType;

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

/** 生产单：自产工单与委托生产单共用。 */
@Entity
@Table(name = "mfg_order")
@Getter
@Setter
@NoArgsConstructor
public class MfgOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String moNo;

    @Enumerated(EnumType.STRING)
    private MoType moType;

    private Long factoryId;

    private Long fgItemId;

    private BigDecimal plannedQty;

    private LocalDate plannedDate;

    @Enumerated(EnumType.STRING)
    private MoStatus status;

    private Long outBatchId;

    private BigDecimal actualQty;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime issuedAt;

    private LocalDateTime completedAt;

    /** 这张单用的配方版本：未发料时跟随最新生效版本，发料后锁定。 */
    private Long formulaVersionId;

    private String cancelReason;

    private String cancelledBy;

    private LocalDateTime cancelledAt;

    /** 委外加工含税单位加工费（仅委托生产单用于对账）。 */
    private BigDecimal processingUnitFee;

    /** 委外加工费税率（%）。 */
    private BigDecimal processingTaxRate;

    /** 委外发料转入的受托厂寄存仓 ID（仅受托厂配置了寄存仓时非空，完工核销或取消时从此仓扣减）。 */
    private Long oemWarehouseId;
}
