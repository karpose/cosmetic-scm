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
            @DecimalMin(value = "0", inclusive = false) BigDecimal limitPct,
            boolean banned,
            @Min(1) @Max(120) Integer shelfMonths,
            @Size(max = 80) String filingNo,
            @Positive BigDecimal netG,
            Boolean active) {
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
            boolean criticalSupplier) {
    }

    /** licenseState: OK / EXPIRING（60 天内到期）/ EXPIRED / NONE */
    public record PartnerView(Long id, String code, String name, PartnerKind kind, String kindLabel,
                              String licenseNo, LocalDate licenseExpiry, String licenseScope,
                              String licenseState, Long licenseDaysLeft, boolean usable,
                              String batchPrefix, String channel, int minShelfPct, String contact, String address,
                              boolean qualified, boolean criticalSupplier) {
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
                             @NotNull @Positive BigDecimal qtyPer,
                             @NotNull SupplyMode supplyMode) {
    }

    public record BomReq(@NotEmpty(message = "至少一行物料") @Valid List<BomLineReq> lines) {
    }

    public record BomLineView(Long itemId, String itemCode, String itemName, String itemType, String unit,
                              BigDecimal qtyPer, SupplyMode supplyMode, String checkLevel,
                              BigDecimal concentrationPct, String checkMessage) {
    }

    public record BomView(Long fgItemId, String fgCode, String fgName, BigDecimal netG,
                          LocalDateTime updatedAt, String updatedBy, List<BomLineView> lines) {
    }
}
