package com.cosmetic.scm.master;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.rules.FormulaCompliance;
import com.cosmetic.scm.master.MasterDtos.BomLineReq;
import com.cosmetic.scm.master.MasterDtos.BomLineView;
import com.cosmetic.scm.master.MasterDtos.BomReq;
import com.cosmetic.scm.master.MasterDtos.BomView;
import com.cosmetic.scm.master.MasterDtos.ItemReq;
import com.cosmetic.scm.master.MasterDtos.PartnerReq;
import com.cosmetic.scm.master.MasterDtos.PartnerView;
import com.cosmetic.scm.master.MasterDtos.WarehouseReq;
import com.cosmetic.scm.master.MasterDtos.WarehouseView;
import com.cosmetic.scm.security.CurrentUser;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MasterService {

    /** 资质到期前多少天开始提醒。 */
    public static final int LICENSE_WARN_DAYS = 60;

    private final ItemRepository items;
    private final PartnerRepository partners;
    private final WarehouseRepository warehouses;
    private final BomRepository boms;
    private final BomLineRepository bomLines;
    private final MasterLookup lookup;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    // ---------- 物料 ----------

    @Transactional(readOnly = true)
    public List<Item> listItems() {
        return items.findAll().stream()
                .sorted(Comparator.comparing((Item i) -> i.getItemType().ordinal()).thenComparing(Item::getCode))
                .toList();
    }

    @Transactional
    public Item createItem(ItemReq req) {
        if (items.existsByCode(req.code())) {
            throw new BizException("物料编码 " + req.code() + " 已存在");
        }
        Item item = new Item();
        item.setCode(req.code().trim());
        applyItem(item, req);
        items.save(item);
        audit.log("新增物料", item.getCode(), item.getName() + "（" + item.getItemType().getLabel() + "）");
        return item;
    }

    @Transactional
    public Item updateItem(Long id, ItemReq req) {
        Item item = lookup.item(id);
        if (!item.getCode().equals(req.code().trim())) {
            throw new BizException("物料编码创建后不能修改");
        }
        if (item.getItemType() != req.itemType()) {
            throw new BizException("物料类型创建后不能修改");
        }
        applyItem(item, req);
        audit.log("修改物料", item.getCode(), item.getName());
        return item;
    }

    private void applyItem(Item item, ItemReq req) {
        if (req.itemType() == ItemType.FG && req.shelfMonths() == null) {
            throw new BizException("成品必须填写保质期（月）");
        }
        item.setName(req.name().trim());
        item.setItemType(req.itemType());
        item.setUnit(req.unit().trim());
        item.setInci(blankToNull(req.inci()));
        item.setLimitPct(req.itemType() == ItemType.RM ? req.limitPct() : null);
        item.setBanned(req.itemType() == ItemType.RM && req.banned());
        item.setShelfMonths(req.shelfMonths());
        item.setFilingNo(req.itemType() == ItemType.FG ? blankToNull(req.filingNo()) : null);
        item.setNetG(req.itemType() == ItemType.FG ? req.netG() : null);
        item.setActive(req.active() == null || req.active());
    }

    // ---------- 往来单位 ----------

    @Transactional(readOnly = true)
    public List<PartnerView> listPartners() {
        LocalDate today = time.today();
        return partners.findAll().stream()
                .sorted(Comparator.comparing((Partner p) -> p.getKind().ordinal()).thenComparing(Partner::getCode))
                .map(p -> toView(p, today))
                .toList();
    }

    @Transactional
    public PartnerView createPartner(PartnerReq req) {
        if (partners.existsByCode(req.code())) {
            throw new BizException("往来单位编码 " + req.code() + " 已存在");
        }
        Partner p = new Partner();
        p.setCode(req.code().trim());
        applyPartner(p, req);
        partners.save(p);
        audit.log("新增往来单位", p.getCode(), p.getName() + "（" + p.getKind().getLabel() + "）");
        return toView(p, time.today());
    }

    @Transactional
    public PartnerView updatePartner(Long id, PartnerReq req) {
        Partner p = lookup.partner(id);
        if (!p.getCode().equals(req.code().trim())) {
            throw new BizException("往来单位编码创建后不能修改");
        }
        String before = describeLicense(p);
        applyPartner(p, req);
        String after = describeLicense(p);
        audit.log("修改往来单位", p.getCode(), before.equals(after) ? p.getName() : before + " → " + after);
        return toView(p, time.today());
    }

    private void applyPartner(Partner p, PartnerReq req) {
        boolean factory = req.kind() == PartnerKind.OEM || req.kind() == PartnerKind.OWN_FACTORY;
        if (factory && (req.licenseNo() == null || req.licenseNo().isBlank())) {
            throw new BizException("受托生产企业和自有工厂必须登记化妆品生产许可证编号");
        }
        p.setName(req.name().trim());
        p.setKind(req.kind());
        p.setLicenseNo(blankToNull(req.licenseNo()));
        p.setLicenseExpiry(req.licenseExpiry());
        p.setLicenseScope(blankToNull(req.licenseScope()));
        p.setBatchPrefix(factory ? blankToNull(req.batchPrefix()) : null);
        p.setChannel(req.kind() == PartnerKind.CUSTOMER ? blankToNull(req.channel()) : null);
        p.setMinShelfPct(req.kind() == PartnerKind.CUSTOMER && req.minShelfPct() != null ? req.minShelfPct() : 0);
        p.setContact(blankToNull(req.contact()));
        p.setAddress(blankToNull(req.address()));
        p.setQualified(req.qualified());
        p.setCriticalSupplier(req.kind() == PartnerKind.SUPPLIER && req.criticalSupplier());
    }

    public PartnerView toView(Partner p, LocalDate today) {
        String state = "NONE";
        Long daysLeft = null;
        if (p.getLicenseExpiry() != null) {
            daysLeft = ChronoUnit.DAYS.between(today, p.getLicenseExpiry());
            state = daysLeft < 0 ? "EXPIRED" : daysLeft <= LICENSE_WARN_DAYS ? "EXPIRING" : "OK";
        }
        return new PartnerView(p.getId(), p.getCode(), p.getName(), p.getKind(), p.getKind().getLabel(),
                p.getLicenseNo(), p.getLicenseExpiry(), p.getLicenseScope(), state, daysLeft, p.usable(today),
                p.getBatchPrefix(), p.getChannel(), p.getMinShelfPct(), p.getContact(), p.getAddress(),
                p.isQualified(), p.isCriticalSupplier());
    }

    private static String describeLicense(Partner p) {
        return "资质 " + (p.getLicenseNo() == null ? "—" : p.getLicenseNo()) + " 至 "
                + (p.getLicenseExpiry() == null ? "—" : p.getLicenseExpiry()) + (p.isQualified() ? "，合格名录内" : "，不在合格名录");
    }

    // ---------- 仓库 ----------

    @Transactional(readOnly = true)
    public List<WarehouseView> listWarehouses() {
        Map<Long, Partner> pm = lookup.partnerMap();
        return warehouses.findAll().stream()
                .sorted(Comparator.comparing(Warehouse::getCode))
                .map(w -> toView(w, pm))
                .toList();
    }

    @Transactional
    public WarehouseView createWarehouse(WarehouseReq req) {
        if (warehouses.existsByCode(req.code())) {
            throw new BizException("仓库编码 " + req.code() + " 已存在");
        }
        if (req.partnerId() != null) {
            lookup.partner(req.partnerId());
        }
        Warehouse w = new Warehouse();
        w.setCode(req.code().trim());
        w.setName(req.name().trim());
        w.setKind(req.kind());
        w.setPartnerId(req.partnerId());
        warehouses.save(w);
        audit.log("新增仓库", w.getCode(), w.getName() + "（" + w.getKind().getLabel() + "）");
        return toView(w, lookup.partnerMap());
    }

    private static WarehouseView toView(Warehouse w, Map<Long, Partner> pm) {
        Partner p = w.getPartnerId() == null ? null : pm.get(w.getPartnerId());
        return new WarehouseView(w.getId(), w.getCode(), w.getName(), w.getKind(), w.getKind().getLabel(),
                w.getPartnerId(), p == null ? null : p.getName());
    }

    // ---------- BOM ----------

    @Transactional(readOnly = true)
    public List<BomView> listBoms() {
        Map<Long, Item> im = lookup.itemMap();
        return items.findByItemTypeOrderByCode(ItemType.FG).stream()
                .map(fg -> buildBomView(fg, im))
                .toList();
    }

    @Transactional(readOnly = true)
    public BomView getBom(Long fgItemId) {
        Item fg = requireFg(fgItemId);
        return buildBomView(fg, lookup.itemMap());
    }

    /** 只校验不保存，供页面实时提示。 */
    @Transactional(readOnly = true)
    public List<BomLineView> checkBom(Long fgItemId, BomReq req) {
        Item fg = requireFg(fgItemId);
        Map<Long, Item> im = lookup.itemMap();
        return req.lines().stream().map(l -> lineView(fg, requireMaterial(im, l.itemId()), l.qtyPer(), l.supplyMode())).toList();
    }

    @Transactional
    public BomView saveBom(Long fgItemId, BomReq req) {
        Item fg = requireFg(fgItemId);
        Map<Long, Item> im = lookup.itemMap();
        List<String> problems = new ArrayList<>();
        for (BomLineReq l : req.lines()) {
            Item m = requireMaterial(im, l.itemId());
            FormulaCompliance.Check c = check(fg, m, l.qtyPer());
            if (c.blocking()) {
                problems.add(c.message());
            }
        }
        if (!problems.isEmpty()) {
            throw new BizException("配方合规校验未通过：" + String.join("；", problems));
        }
        long distinct = req.lines().stream().map(BomLineReq::itemId).distinct().count();
        if (distinct != req.lines().size()) {
            throw new BizException("同一物料在 BOM 中只能出现一次");
        }
        Bom bom = boms.findByFgItemId(fgItemId).orElseGet(() -> {
            Bom b = new Bom();
            b.setFgItemId(fgItemId);
            return b;
        });
        bom.setUpdatedAt(time.now());
        bom.setUpdatedBy(currentUser.usernameOrSystem());
        boms.save(bom);
        bomLines.deleteByBomId(bom.getId());
        int no = 1;
        for (BomLineReq l : req.lines()) {
            BomLine line = new BomLine();
            line.setBomId(bom.getId());
            line.setLineNo(no++);
            line.setItemId(l.itemId());
            line.setQtyPer(l.qtyPer());
            line.setSupplyMode(l.supplyMode());
            bomLines.save(line);
        }
        audit.log("保存 BOM", fg.getCode(), req.lines().stream()
                .map(l -> im.get(l.itemId()).getCode() + "×" + l.qtyPer().stripTrailingZeros().toPlainString() + "(" + l.supplyMode().getLabel() + ")")
                .collect(Collectors.joining("，")));
        return buildBomView(fg, im);
    }

    /** 供生产模块读取 BOM 行。 */
    @Transactional(readOnly = true)
    public List<BomLine> bomLines(Long fgItemId) {
        return boms.findByFgItemId(fgItemId)
                .map(b -> bomLines.findByBomIdOrderByLineNo(b.getId()))
                .orElse(List.of());
    }

    private BomView buildBomView(Item fg, Map<Long, Item> im) {
        return boms.findByFgItemId(fg.getId())
                .map(b -> new BomView(fg.getId(), fg.getCode(), fg.getName(), fg.getNetG(), b.getUpdatedAt(), b.getUpdatedBy(),
                        bomLines.findByBomIdOrderByLineNo(b.getId()).stream()
                                .map(l -> lineView(fg, im.get(l.getItemId()), l.getQtyPer(), l.getSupplyMode()))
                                .toList()))
                .orElseGet(() -> new BomView(fg.getId(), fg.getCode(), fg.getName(), fg.getNetG(), null, null, List.of()));
    }

    private BomLineView lineView(Item fg, Item m, BigDecimal qtyPer, com.cosmetic.scm.domain.SupplyMode mode) {
        FormulaCompliance.Check c = check(fg, m, qtyPer);
        return new BomLineView(m.getId(), m.getCode(), m.getName(), m.getItemType().name(), m.getUnit(), qtyPer, mode,
                c.level().name(), c.concentrationPct(), c.message());
    }

    private static FormulaCompliance.Check check(Item fg, Item m, BigDecimal qtyPer) {
        return FormulaCompliance.check(m.getName(), m.getItemType() == ItemType.RM, m.isBanned(), m.getLimitPct(),
                qtyPer, m.getUnit(), fg.getNetG());
    }

    private Item requireFg(Long id) {
        Item fg = lookup.item(id);
        if (fg.getItemType() != ItemType.FG) {
            throw new BizException(fg.getName() + " 不是成品，不能维护 BOM");
        }
        return fg;
    }

    private static Item requireMaterial(Map<Long, Item> im, Long id) {
        Item m = im.get(id);
        if (m == null) {
            throw BizException.notFound("物料");
        }
        if (m.getItemType() == ItemType.FG) {
            throw new BizException("BOM 只能包含原料和包材");
        }
        return m;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
