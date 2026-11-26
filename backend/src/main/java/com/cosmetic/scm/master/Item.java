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

    /** CAS 号，保存时校验格式和校验位。法规目录可以按它对照。 */
    @Column(name = "cas_no")
    private String casNo;

    private BigDecimal limitPct;

    private boolean banned;

    private Integer shelfMonths;

    private String filingNo;

    @Column(name = "net_g") // 默认命名策略会映射成 netg
    private BigDecimal netG;

    /** 标签上标注的净含量，如“30mL”；不填时销售记录按净含量（g）写。 */
    @Column(name = "net_label")
    private String netLabel;

    /** 标准成本：元 / 物料单位 */
    private BigDecimal stdCost;

    /** 成品指导价：元 / 件 */
    private BigDecimal listPrice;

    private boolean active = true;

    /** 成品属于眼部护肤类：受托方许可项目要标注具备相应生产条件（检查要点委托生产版第 2 项）。 */
    private boolean eyeCare;

    /** 成品属于儿童护肤类：同上。 */
    private boolean childCare;
}
