package com.cosmetic.scm.sourcing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.security.CurrentUser;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 供应商协议价 / 价目表服务：维护有效协议价，供下采购单自动带价与偏离预警。 */
@Service
@RequiredArgsConstructor
public class SupplierPriceService {

    private final SupplierPriceRepository prices;
    private final MasterLookup lookup;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record PriceReq(@NotNull(message = "请选择供应商") Long supplierId,
                           @NotNull(message = "请选择物料") Long itemId,
                           @NotNull(message = "请填写协议含税单价") @Positive(message = "单价要大于 0")
                           @Digits(integer = 14, fraction = 4, message = "单价最多 14 位整数、4 位小数") BigDecimal unitPrice,
                           @DecimalMin(value = "0", message = "税率不能为负") @DecimalMax(value = "100", message = "税率不能超过 100")
                           @Digits(integer = 3, fraction = 2, message = "税率最多 2 位小数") BigDecimal taxRate,
                           @NotNull(message = "请选择生效日期") LocalDate validFrom,
                           LocalDate validTo,
                           @Size(max = 300) String note) {
    }

    public record PriceView(Long id, Long supplierId, String supplierCode, String supplierName,
                            Long itemId, String itemCode, String itemName, String unit,
                            BigDecimal unitPrice, BigDecimal taxRate, LocalDate validFrom, LocalDate validTo,
                            boolean effectiveToday, boolean active, String note,
                            String updatedBy, LocalDateTime updatedAt) {
    }

    @Transactional(readOnly = true)
    public List<PriceView> list(Long supplierId, Long itemId, Boolean activeOnly) {
        LocalDate today = time.today();
        Map<Long, Partner> pm = lookup.partnerMap();
        Map<Long, Item> im = lookup.itemMap();
        return prices.findAllByOrderByIdDesc().stream()
                .filter(p -> supplierId == null || supplierId.equals(p.getSupplierId()))
                .filter(p -> itemId == null || itemId.equals(p.getItemId()))
                .filter(p -> !Boolean.TRUE.equals(activeOnly) || p.isActive())
                .map(p -> toView(p, pm.get(p.getSupplierId()), im.get(p.getItemId()), today))
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<SupplierPrice> effectivePrice(Long supplierId, Long itemId, LocalDate onDate) {
        LocalDate date = onDate != null ? onDate : time.today();
        return prices.findBySupplierIdAndItemIdAndActiveTrueOrderByValidFromDescIdDesc(supplierId, itemId).stream()
                .filter(p -> p.isEffectiveOn(date))
                .findFirst();
    }

    @Transactional(readOnly = true)
    public PriceView effectiveView(Long supplierId, Long itemId, LocalDate onDate) {
        LocalDate date = onDate != null ? onDate : time.today();
        return effectivePrice(supplierId, itemId, date)
                .map(p -> toView(p, lookup.partner(p.getSupplierId()), lookup.item(p.getItemId()), time.today()))
                .orElse(null);
    }

    @Transactional
    public PriceView create(PriceReq req) {
        Partner s = lookup.partner(req.supplierId());
        if (s.getKind() != PartnerKind.SUPPLIER) {
            throw new BizException(s.getName() + " 不是物料供应商");
        }
        Item item = lookup.item(req.itemId());
        if (item.getItemType() == ItemType.FG) {
            throw new BizException("协议价仅适用于原料、包材或半成品，" + item.getName() + " 是成品");
        }
        if (req.validTo() != null && req.validTo().isBefore(req.validFrom())) {
            throw new BizException("失效日期不能早于生效日期");
        }
        LocalDateTime now = time.now();
        String user = currentUser.usernameOrSystem();
        SupplierPrice p = new SupplierPrice();
        p.setSupplierId(s.getId());
        p.setItemId(item.getId());
        p.setUnitPrice(req.unitPrice());
        BigDecimal rate = req.taxRate() != null ? req.taxRate()
                : (s.getDefaultTaxRate() != null ? s.getDefaultTaxRate() : new BigDecimal("13.00"));
        p.setTaxRate(rate);
        p.setValidFrom(req.validFrom());
        p.setValidTo(req.validTo());
        p.setActive(true);
        p.setNote(req.note() == null || req.note().isBlank() ? null : req.note().trim());
        p.setCreatedBy(user);
        p.setCreatedAt(now);
        p.setUpdatedBy(user);
        p.setUpdatedAt(now);
        prices.save(p);
        audit.logMoney("新增供应商协议价", s.getCode() + "/" + item.getCode(),
                s.getName() + " · " + item.getName() + " ¥" + p.getUnitPrice().stripTrailingZeros().toPlainString()
                        + "（税率 " + p.getTaxRate().stripTrailingZeros().toPlainString() + "%）");
        return toView(p, s, item, time.today());
    }

    @Transactional
    public PriceView deactivate(Long id) {
        SupplierPrice p = prices.findById(id).orElseThrow(() -> BizException.notFound("供应商协议价"));
        p.setActive(false);
        p.setUpdatedBy(currentUser.usernameOrSystem());
        p.setUpdatedAt(time.now());
        prices.save(p);
        Partner s = lookup.partner(p.getSupplierId());
        Item item = lookup.item(p.getItemId());
        audit.logMoney("停用供应商协议价", s.getCode() + "/" + item.getCode(),
                s.getName() + " · " + item.getName() + " ¥" + p.getUnitPrice().stripTrailingZeros().toPlainString());
        return toView(p, s, item, time.today());
    }

    private static PriceView toView(SupplierPrice p, Partner s, Item i, LocalDate today) {
        return new PriceView(p.getId(), p.getSupplierId(), s == null ? null : s.getCode(), s == null ? "-" : s.getName(),
                p.getItemId(), i == null ? null : i.getCode(), i == null ? "-" : i.getName(), i == null ? "" : i.getUnit(),
                p.getUnitPrice(), p.getTaxRate(), p.getValidFrom(), p.getValidTo(),
                p.isEffectiveOn(today), p.isActive(), p.getNote(), p.getUpdatedBy(), p.getUpdatedAt());
    }
}
