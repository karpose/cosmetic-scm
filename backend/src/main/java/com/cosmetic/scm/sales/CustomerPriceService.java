package com.cosmetic.scm.sales;

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

/** 客户协议价 / 渠道供货价目表服务：维护客户 + 成品含税单价，供销售出库与经销商审核自动带价。 */
@Service
@RequiredArgsConstructor
public class CustomerPriceService {

    private final CustomerPriceRepository repo;
    private final MasterLookup lookup;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record SaveReq(@NotNull(message = "请选择客户") Long customerId,
                          @NotNull(message = "请选择成品") Long itemId,
                          @NotNull(message = "请填写含税供货价") @Positive(message = "单价要大于 0")
                          @Digits(integer = 14, fraction = 4) BigDecimal unitPrice,
                          @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal taxRate,
                          LocalDate validFrom,
                          LocalDate validTo,
                          @Size(max = 300) String note) {
    }

    public record PriceView(Long id, Long customerId, String customerCode, String customerName,
                            Long itemId, String itemCode, String itemName, String unit,
                            BigDecimal unitPrice, BigDecimal taxRate, LocalDate validFrom, LocalDate validTo,
                            boolean active, boolean effectiveToday, String note,
                            String updatedBy, LocalDateTime updatedAt) {
    }

    @Transactional(readOnly = true)
    public List<PriceView> list(Long customerId, Long itemId, boolean activeOnly) {
        LocalDate today = time.today();
        List<CustomerPrice> list = customerId != null ? repo.findByCustomerIdOrderByIdDesc(customerId)
                : repo.findAllByOrderByIdDesc();
        Map<Long, Partner> pm = lookup.partnerMap();
        Map<Long, Item> im = lookup.itemMap();
        return list.stream()
                .filter(p -> itemId == null || itemId.equals(p.getItemId()))
                .filter(p -> !activeOnly || p.isEffectiveOn(today))
                .map(p -> toView(p, pm.get(p.getCustomerId()), im.get(p.getItemId()), today))
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<CustomerPrice> effectivePrice(Long customerId, Long itemId, LocalDate onDate) {
        if (customerId == null || itemId == null) {
            return Optional.empty();
        }
        LocalDate date = onDate != null ? onDate : time.today();
        return repo.findByCustomerIdAndItemIdAndActiveTrueOrderByValidFromDescIdDesc(customerId, itemId).stream()
                .filter(p -> p.isEffectiveOn(date))
                .findFirst();
    }

    @Transactional
    public PriceView create(SaveReq req) {
        Partner customer = lookup.partner(req.customerId());
        if (customer.getKind() != PartnerKind.CUSTOMER) {
            throw new BizException(customer.getName() + " 不是客户渠道");
        }
        Item item = lookup.item(req.itemId());
        if (item.getItemType() != ItemType.FG) {
            throw new BizException("客户协议价只能维护成品，" + item.getName() + " 不是成品");
        }
        LocalDate today = time.today();
        LocalDate from = req.validFrom() != null ? req.validFrom() : today;
        if (req.validTo() != null && req.validTo().isBefore(from)) {
            throw new BizException("失效日期不能早于生效日期");
        }
        BigDecimal rate = req.taxRate() != null ? req.taxRate()
                : (customer.getDefaultTaxRate() != null ? customer.getDefaultTaxRate() : new BigDecimal("13.00"));
        CustomerPrice cp = new CustomerPrice();
        cp.setCustomerId(customer.getId());
        cp.setItemId(item.getId());
        cp.setUnitPrice(req.unitPrice());
        cp.setTaxRate(rate);
        cp.setValidFrom(from);
        cp.setValidTo(req.validTo());
        cp.setActive(true);
        cp.setNote(req.note() == null || req.note().isBlank() ? null : req.note().trim());
        String user = currentUser.usernameOrSystem();
        LocalDateTime now = time.now();
        cp.setCreatedBy(user);
        cp.setCreatedAt(now);
        cp.setUpdatedBy(user);
        cp.setUpdatedAt(now);
        repo.save(cp);
        audit.logMoney("维护客户协议价", customer.getCode() + "/" + item.getCode(),
                customer.getName() + " · " + item.getName() + " ¥" + req.unitPrice().toPlainString());
        return toView(cp, customer, item, today);
    }

    @Transactional
    public PriceView toggleActive(Long id, boolean active) {
        CustomerPrice cp = repo.findById(id).orElseThrow(() -> BizException.notFound("客户协议价"));
        cp.setActive(active);
        cp.setUpdatedBy(currentUser.usernameOrSystem());
        cp.setUpdatedAt(time.now());
        repo.save(cp);
        Partner c = lookup.partner(cp.getCustomerId());
        Item i = lookup.item(cp.getItemId());
        audit.logMoney(active ? "启用客户协议价" : "停用客户协议价", c.getCode() + "/" + i.getCode(), c.getName() + " · " + i.getName());
        return toView(cp, c, i, time.today());
    }

    private PriceView toView(CustomerPrice p, Partner c, Item i, LocalDate today) {
        return new PriceView(p.getId(), p.getCustomerId(), c.getCode(), c.getName(),
                p.getItemId(), i.getCode(), i.getName(), i.getUnit(),
                p.getUnitPrice(), p.getTaxRate(), p.getValidFrom(), p.getValidTo(),
                p.isActive(), p.isEffectiveOn(today), p.getNote(), p.getUpdatedBy(), p.getUpdatedAt());
    }
}
