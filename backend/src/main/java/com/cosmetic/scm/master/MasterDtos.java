package com.cosmetic.scm.master;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.SupplyMode;
import com.cosmetic.scm.domain.WarehouseKind;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class MasterDtos {

    private MasterDtos() {
    }

    public static final String CODE_REGEX = "^[A-Za-z0-9][A-Za-z0-9_.\\-]{0,39}$";
    public static final String CODE_MSG = "只能用字母、数字和 _ . -，最长 40 位";

    public record ItemReq(
            @NotBlank @Pattern(regexp = CODE_REGEX, message = CODE_MSG) String code,
            @NotBlank @Size(max = 100) String name,
            @NotNull ItemType itemType,
            @NotBlank @Size(max = 16) String unit,
            @Size(max = 200) String inci,
            @DecimalMin(value = "0", inclusive = false) @jakarta.validation.constraints.Digits(integer = 5, fraction = 4, message = "限用浓度最多 4 位小数") BigDecimal limitPct,
            boolean banned,
            @Min(1) @Max(120) Integer shelfMonths,
            @Size(max = 60) String filingNo,
            @Positive @jakarta.validation.constraints.Digits(integer = 9, fraction = 3, message = "净含量最多 9 位整数、3 位小数") BigDecimal netG,
            Boolean active,
            @DecimalMin(value = "0", message = "标准成本不能为负") @jakarta.validation.constraints.Digits(integer = 14, fraction = 4, message = "标准成本最多 14 位整数、4 位小数") BigDecimal stdCost,
            @DecimalMin(value = "0", message = "指导价不能为负") @jakarta.validation.constraints.Digits(integer = 14, fraction = 4, message = "指导价最多 14 位整数、4 位小数") BigDecimal listPrice,
            @Size(max = 40) String casNo) {
        public ItemReq(String code, String name, ItemType itemType, String unit, String inci, BigDecimal limitPct, boolean banned, Integer shelfMonths,
                       String filingNo, BigDecimal netG, Boolean active, BigDecimal stdCost, BigDecimal listPrice) {
            this(code, name, itemType, unit, inci, limitPct, banned, shelfMonths, filingNo, netG, active, stdCost, listPrice, null);
        }
    }

    public record PartnerReq(
            @NotBlank @Pattern(regexp = CODE_REGEX, message = CODE_MSG) String code,
            @NotBlank @Size(max = 100) String name,
            @NotNull PartnerKind kind,
            @Size(max = 100) String licenseNo,
            LocalDate licenseExpiry,
            @Size(max = 300) String licenseScope,
            @Pattern(regexp = "^[A-Za-z0-9]{0,8}$", message = "批号前缀只能用字母和数字，最长 8 位") String batchPrefix,
            @Size(max = 40) String channel,
            @Min(0) @Max(100) Integer minShelfPct,
            @Size(max = 100) String contact,
            @Size(max = 200) String address,
            boolean qualified,
            boolean criticalSupplier,
            @Size(max = 40) String taxId,
            @Size(max = 120) String bankName,
            @Size(max = 60) String bankAccount,
            @Min(0) @Max(365) Integer defaultCreditDays,
            @DecimalMin(value = "0", message = "默认税率不能为负") @jakarta.validation.constraints.DecimalMax(value = "100", message = "默认税率不能超过 100")
            @jakarta.validation.constraints.Digits(integer = 3, fraction = 2, message = "默认税率最多 2 位小数") BigDecimal defaultTaxRate,
            @Size(max = 40) String financeCode,
            @DecimalMin(value = "0", message = "信用额度不能为负") @jakarta.validation.constraints.Digits(integer = 14, fraction = 2, message = "信用额度最多 2 位小数") BigDecimal creditLimit) {
        public PartnerReq(String code, String name, PartnerKind kind, String licenseNo, LocalDate licenseExpiry,
                          String licenseScope, String batchPrefix, String channel, Integer minShelfPct,
                          String contact, String address, boolean qualified, boolean criticalSupplier) {
            this(code, name, kind, licenseNo, licenseExpiry, licenseScope, batchPrefix, channel, minShelfPct,
                    contact, address, qualified, criticalSupplier, null, null, null, null, null, null, null);
        }

        public PartnerReq(String code, String name, PartnerKind kind, String licenseNo, LocalDate licenseExpiry,
                          String licenseScope, String batchPrefix, String channel, Integer minShelfPct,
                          String contact, String address, boolean qualified, boolean criticalSupplier,
                          String taxId, String bankName, String bankAccount,
                          Integer defaultCreditDays, BigDecimal defaultTaxRate, String financeCode) {
            this(code, name, kind, licenseNo, licenseExpiry, licenseScope, batchPrefix, channel, minShelfPct,
                    contact, address, qualified, criticalSupplier, taxId, bankName, bankAccount,
                    defaultCreditDays, defaultTaxRate, financeCode, null);
        }
    }

    /** licenseState: OK / EXPIRING（60 天内到期）/ EXPIRED / NONE */
    public record PartnerView(Long id, String code, String name, PartnerKind kind, String kindLabel,
                              String licenseNo, LocalDate licenseExpiry, String licenseScope,
                              String licenseState, Long licenseDaysLeft, boolean usable,
                              String batchPrefix, String channel, int minShelfPct, String contact, String address,
                              boolean qualified, boolean criticalSupplier,
                              String taxId, String bankName, String bankAccount,
                              Integer defaultCreditDays, BigDecimal defaultTaxRate, String financeCode,
                              BigDecimal creditLimit) {
    }

    public record WarehouseReq(
            @NotBlank @Pattern(regexp = CODE_REGEX, message = CODE_MSG) String code,
            @NotBlank @Size(max = 100) String name,
            @NotNull WarehouseKind kind,
            Long partnerId) {
    }

    public record WarehouseView(Long id, String code, String name, WarehouseKind kind, String kindLabel,
                                Long partnerId, String partnerName) {
    }

    public record BomLineReq(@NotNull Long itemId,
                             @NotNull @Positive @jakarta.validation.constraints.Digits(integer = 12, fraction = 6, message = "单耗最多 12 位整数、6 位小数") BigDecimal qtyPer,
                             @NotNull SupplyMode supplyMode) {
    }

    public record BomReq(@NotEmpty(message = "至少一行物料") @Valid List<BomLineReq> lines) {
    }

    public record BomLineView(Long itemId, String itemCode, String itemName, String itemType, String unit,
                              BigDecimal qtyPer, SupplyMode supplyMode, String checkLevel,
                              BigDecimal concentrationPct, String checkMessage) {
    }

    /** materialCost：按 BOM 和物料标准成本算出的每件物料成本；costComplete 为 false 表示有物料没填标准成本 */
    public record BomView(Long fgItemId, String fgCode, String fgName, BigDecimal netG,
                          LocalDateTime updatedAt, String updatedBy, List<BomLineView> lines,
                          BigDecimal materialCost, boolean costComplete) {
    }
}
