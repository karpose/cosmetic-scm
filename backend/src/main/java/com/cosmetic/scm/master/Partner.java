package com.cosmetic.scm.master;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.cosmetic.scm.domain.PartnerKind;

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

/** 往来单位：物料供应商、受托生产企业、自有工厂、客户渠道。 */
@Entity
@Table(name = "partner")
@Getter
@Setter
@NoArgsConstructor
public class Partner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String code;

    private String name;

    @Enumerated(EnumType.STRING)
    private PartnerKind kind;

    private String licenseNo;

    private LocalDate licenseExpiry;

    private String licenseScope;

    private String batchPrefix;

    private String channel;

    private int minShelfPct;

    private String contact;

    private String address;

    private boolean qualified = true;

    private boolean criticalSupplier;

    private String taxId;

    private String bankName;

    private String bankAccount;

    private Integer defaultCreditDays;

    private BigDecimal defaultTaxRate;

    private String financeCode;

    private BigDecimal creditLimit;

    /** 资质已过期。 */
    public boolean licenseExpired(LocalDate today) {
        return licenseExpiry != null && licenseExpiry.isBefore(today);
    }

    /** 可下单：在合格名录内，且资质未过期。客户不受此限制。 */
    public boolean usable(LocalDate today) {
        if (kind == PartnerKind.CUSTOMER) {
            return true;
        }
        return qualified && !licenseExpired(today);
    }
}
