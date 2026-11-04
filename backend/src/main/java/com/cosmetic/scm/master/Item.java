package com.cosmetic.scm.master;

import java.math.BigDecimal;

import com.cosmetic.scm.domain.ItemType;

import jakarta.persistence.Column;
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

@Entity
@Table(name = "item")
@Getter
@Setter
@NoArgsConstructor
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String code;

    private String name;

    @Enumerated(EnumType.STRING)
    private ItemType itemType;

    private String unit;

    private String inci;

    private BigDecimal limitPct;

    private boolean banned;

    private Integer shelfMonths;

    private String filingNo;

    @Column(name = "net_g") // 默认命名策略会映射成 netg
    private BigDecimal netG;

    /** 标准成本：元 / 物料单位 */
    private BigDecimal stdCost;

    /** 成品指导价：元 / 件 */
    private BigDecimal listPrice;

    private boolean active = true;
}
