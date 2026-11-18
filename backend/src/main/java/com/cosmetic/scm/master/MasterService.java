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
import com.cosmetic.scm.common.FieldDiff;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.FilingStatus;
import com.cosmetic.scm.domain.FilingType;
import com.cosmetic.scm.domain.FormulaStatus;
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
import com.cosmetic.scm.plm.FormulaLine;
import com.cosmetic.scm.plm.FormulaLineRepository;
import com.cosmetic.scm.plm.FormulaVersion;
import com.cosmetic.scm.plm.FormulaVersionRepository;
import com.cosmetic.scm.plm.ProductFiling;
import com.cosmetic.scm.plm.ProductFilingRepository;
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
    private final FormulaVersionRepository formulaVersions;
    private final RegulatoryService regulatory;
    private final FormulaLineRepository formulaLines;
    private final ProductFilingRepository filings;
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
        recordFilingFromMaster(item);
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
        String oldFiling = item.getFilingNo();
        String newFiling = item.getItemType() == ItemType.FG ? blankToNull(req.filingNo()) : null;
        boolean filingChanged = !java.util.Objects.equals(oldFiling, newFiling);
        if (filingChanged && !filings.findByFgItemIdAndStatusOrderByIdDesc(id, FilingStatus.FILED).isEmpty()) {
            throw new BizException(item.getName() + " 的备案编号由备案记录决定，修改请到“配方与备案 → 备案登记”登记新的备案");
        }
        Map<String, Object> before = itemFields(item);
        applyItem(item, req);
        if (filingChanged) {
            recordFilingFromMaster(item);
        }
        String changes = FieldDiff.of(before, itemFields(item));
        audit.log("修改物料", item.getCode(), item.getName() + "：" + (changes.isEmpty() ? "内容没有变化" : changes));
        return item;
    }

    /**
     * 上线初始化时可以在主数据（或 CSV）里直接填已有的备案编号，这里同时补一条已完成的备案记录。
     * 有了记录之后编号只能走备案登记改，上市放行看到的编号都能追溯到记录。
     */
    private void recordFilingFromMaster(Item item) {
        if (item.getItemType() != ItemType.FG || item.getFilingNo() == null) {
            return;
        }
        ProductFiling f = new ProductFiling();
        f.setFgItemId(item.getId());
        f.setFilingType(FilingType.GENERAL);
        f.setFilingNo(item.getFilingNo());
        f.setStatus(FilingStatus.FILED);
        f.setNote("主数据录入的已有编号，请核对类型和日期");
        f.setCreatedBy(currentUser.usernameOrSystem());
        f.setCreatedAt(time.now());
        filings.save(f);
    }

    private static Map<String, Object> itemFields(Item i) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("名称", i.getName());
        m.put("单位", i.getUnit());
        m.put("INCI", i.getInci());
        m.put("CAS 号", i.getCasNo());
        m.put("限用浓度上限", i.getLimitPct());
        m.put("禁用", i.isBanned());
        m.put("保质期（月）", i.getShelfMonths());
        m.put("备案编号", i.getFilingNo());
        m.put("净含量", i.getNetG());
        m.put("启用", i.isActive());
        m.put("标准成本", i.getStdCost());
        m.put("指导价", i.getListPrice());
        return m;
    }

    private void applyItem(Item item, ItemReq req) {
        if (req.itemType() == ItemType.FG && req.shelfMonths() == null) {
            throw new BizException("成品必须填写保质期（月）");
        }
        item.setName(req.name().trim());
        item.setItemType(req.itemType());
        item.setUnit(req.unit().trim());
        item.setInci(blankToNull(req.inci()));
        if (req.casNo() == null || req.casNo().isBlank()) {
            item.setCasNo(null);
        } else {
            item.setCasNo(com.cosmetic.scm.domain.rules.CasNumber.normalize(req.casNo())
                    .orElseThrow(() -> new BizException("CAS 号 " + req.casNo().trim() + " 格式或校验位不对，应为“数字-两位数字-一位校验位”，如 7732-18-5")));
        }
        item.setLimitPct(req.itemType() == ItemType.RM ? req.limitPct() : null);
        item.setBanned(req.itemType() == ItemType.RM && req.banned());
        item.setShelfMonths(req.shelfMonths());
        item.setFilingNo(req.itemType() == ItemType.FG ? blankToNull(req.filingNo()) : null);
        item.setNetG(req.itemType() == ItemType.FG ? req.netG() : null);
        item.setActive(req.active() == null || req.active());
        item.setStdCost(req.stdCost());
        item.setListPrice(req.itemType() == ItemType.FG ? req.listPrice() : null);
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
        Map<String, Object> before = partnerFields(p);
        applyPartner(p, req);
        String changes = FieldDiff.of(before, partnerFields(p));
        audit.log("修改往来单位", p.getCode(), p.getName() + "：" + (changes.isEmpty() ? "内容没有变化" : changes));
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
        p.setTaxId(blankToNull(req.taxId()));
        p.setBankName(blankToNull(req.bankName()));
        p.setBankAccount(blankToNull(req.bankAccount()));
        p.setDefaultCreditDays(req.defaultCreditDays());
        p.setDefaultTaxRate(req.defaultTaxRate());
        p.setFinanceCode(blankToNull(req.financeCode()));
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
                p.isQualified(), p.isCriticalSupplier(),
                p.getTaxId(), p.getBankName(), p.getBankAccount(),
                p.getDefaultCreditDays(), p.getDefaultTaxRate(), p.getFinanceCode());
    }

    private static Map<String, Object> partnerFields(Partner p) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("名称", p.getName());
        m.put("类别", p.getKind().getLabel());
        m.put("资质编号", p.getLicenseNo());
        m.put("资质有效期", p.getLicenseExpiry());
        m.put("许可范围", p.getLicenseScope());
        m.put("批号前缀", p.getBatchPrefix());
        m.put("渠道", p.getChannel());
        m.put("剩余效期接收标准 %", p.getMinShelfPct());
        m.put("联系人", p.getContact());
        m.put("地址", p.getAddress());
        m.put("合格名录", p.isQualified());
        m.put("关键供应商", p.isCriticalSupplier());
        m.put("税号", p.getTaxId());
        m.put("开户银行", p.getBankName());
        m.put("银行账号", p.getBankAccount());
        m.put("默认账期（天）", p.getDefaultCreditDays());
        m.put("默认税率（%）", p.getDefaultTaxRate());
        m.put("财务客商编码", p.getFinanceCode());
        return m;
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

    /**
     * 录入成品的第一版配方，直接生效（上线初始化）。已有生效配方后不能在这里改，
     * 要走“配方与备案”的变更流程，由质量安全负责人签批。
     */
    @Transactional
    public BomView saveBom(Long fgItemId, BomReq req) {
        Item fg = requireFg(fgItemId);
        if (formulaVersions.findFirstByFgItemIdAndStatus(fgItemId, FormulaStatus.EFFECTIVE).isPresent()) {
            throw new BizException(fg.getName() + " 已有生效配方，修改请到“配方与备案”发起变更，经质量安全负责人签批后生效");
        }
        validateBomLines(fg, req.lines());
        applyBom(fg, req.lines());
        FormulaVersion v = new FormulaVersion();
        v.setFgItemId(fgItemId);
        v.setVersionNo(formulaVersions.findByFgItemIdOrderByVersionNoDesc(fgItemId).stream()
                .mapToInt(FormulaVersion::getVersionNo).max().orElse(0) + 1);
        v.setStatus(FormulaStatus.EFFECTIVE);
        v.setChangeNote("首版配方录入");
        v.setCreatedBy(currentUser.usernameOrSystem());
        v.setCreatedAt(time.now());
        v.setApprovedBy(currentUser.usernameOrSystem());
        v.setApprovedAt(time.now());
        formulaVersions.save(v);
        int no = 1;
        for (BomLineReq l : req.lines()) {
            FormulaLine line = new FormulaLine();
            line.setVersionId(v.getId());
            line.setLineNo(no++);
            line.setItemId(l.itemId());
            line.setQtyPer(l.qtyPer());
            line.setSupplyMode(l.supplyMode());
            formulaLines.save(line);
        }
        return buildBomView(fg, lookup.itemMap());
    }

    /** 禁用、超限和重复物料的校验。配方变更的提交和生效都要过这一关。 */
    public void validateBomLines(Item fg, List<BomLineReq> lines) {
        Map<Long, Item> im = lookup.itemMap();
        List<String> problems = new ArrayList<>();
        for (BomLineReq l : lines) {
            Item m = requireMaterial(im, l.itemId());
            FormulaCompliance.Check c = check(fg, m, l.qtyPer());
            if (c.blocking()) {
                problems.add(c.message());
            }
        }
        if (!problems.isEmpty()) {
            throw new BizException("配方合规校验未通过：" + String.join("；", problems));
        }
        long distinct = lines.stream().map(BomLineReq::itemId).distinct().count();
        if (distinct != lines.size()) {
            throw new BizException("同一物料在 BOM 中只能出现一次");
        }
    }

    /** 把内容写入当前 BOM。调用方负责校验和版本记录。 */
    public void applyBom(Item fg, List<BomLineReq> lines) {
        Map<Long, Item> im = lookup.itemMap();
        Long fgItemId = fg.getId();
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
        for (BomLineReq l : lines) {
            BomLine line = new BomLine();
            line.setBomId(bom.getId());
            line.setLineNo(no++);
            line.setItemId(l.itemId());
            line.setQtyPer(l.qtyPer());
            line.setSupplyMode(l.supplyMode());
            bomLines.save(line);
        }
        audit.log("保存 BOM", fg.getCode(), lines.stream()
                .map(l -> im.get(l.itemId()).getCode() + "×" + l.qtyPer().stripTrailingZeros().toPlainString() + "(" + l.supplyMode().getLabel() + ")")
                .collect(Collectors.joining("，")));
    }

    /** 当前生效的配方版本号（ID）；还没录入配方时为空。 */
    @Transactional(readOnly = true)
    public Long effectiveVersionId(Long fgItemId) {
        return formulaVersions.findFirstByFgItemIdAndStatus(fgItemId, FormulaStatus.EFFECTIVE).map(FormulaVersion::getId).orElse(null);
    }

    /** 生产单用：读某个配方版本的内容（不是当前 BOM），配方变更后已发料的单子仍按当时的用量。 */
    @Transactional(readOnly = true)
    public List<BomLine> versionLines(Long versionId) {
        return formulaLines.findByVersionIdOrderByLineNo(versionId).stream().map(f -> {
            BomLine l = new BomLine();
            l.setLineNo(f.getLineNo());
            l.setItemId(f.getItemId());
            l.setQtyPer(f.getQtyPer());
            l.setSupplyMode(f.getSupplyMode());
            return l;
        }).toList();
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
                .map(b -> {
                    List<BomLine> ls = bomLines.findByBomIdOrderByLineNo(b.getId());
                    Cost cost = materialCost(ls, im);
                    return new BomView(fg.getId(), fg.getCode(), fg.getName(), fg.getNetG(), b.getUpdatedAt(), b.getUpdatedBy(),
                            ls.stream().map(l -> lineView(fg, im.get(l.getItemId()), l.getQtyPer(), l.getSupplyMode())).toList(),
                            cost.value(), cost.complete());
                })
                .orElseGet(() -> new BomView(fg.getId(), fg.getCode(), fg.getName(), fg.getNetG(), null, null, List.of(), null, false));
    }

    private record Cost(BigDecimal value, boolean complete) {
    }

    /** 每件成品的物料成本 = Σ 单耗 × 物料标准成本；有物料没填成本时 complete 为 false。 */
    private static Cost materialCost(List<BomLine> lines, Map<Long, Item> im) {
        if (lines.isEmpty()) {
            return new Cost(null, false);
        }
        BigDecimal sum = BigDecimal.ZERO;
        boolean complete = true;
        for (BomLine l : lines) {
            BigDecimal c = im.get(l.getItemId()).getStdCost();
            if (c == null) {
                complete = false;
            } else {
                sum = sum.add(c.multiply(l.getQtyPer()));
            }
        }
        return new Cost(sum.setScale(4, java.math.RoundingMode.HALF_UP), complete);
    }

    /**
     * 估值用的单位成本：填了标准成本就用标准成本；成品没填时用 BOM 物料成本（物料成本都齐时）；都没有为空。
     * 看板、补货建议换算金额都走这里，口径一致。
     */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> unitCosts() {
        Map<Long, Item> im = lookup.itemMap();
        Map<Long, BigDecimal> out = new java.util.HashMap<>();
        for (Item i : im.values()) {
            if (i.getStdCost() != null) {
                out.put(i.getId(), i.getStdCost());
            } else if (i.getItemType() == ItemType.FG) {
                Cost c = materialCost(bomLines(i.getId()), im);
                if (c.complete()) {
                    out.put(i.getId(), c.value());
                }
            }
        }
        return out;
    }

    public BomLineView lineView(Item fg, Item m, BigDecimal qtyPer, com.cosmetic.scm.domain.SupplyMode mode) {
        FormulaCompliance.Check c = check(fg, m, qtyPer);
        return new BomLineView(m.getId(), m.getCode(), m.getName(), m.getItemType().name(), m.getUnit(), qtyPer, mode,
                c.level().name(), c.concentrationPct(), c.message());
    }

    /** 物料上人工标的和法规目录都要看，取严的。 */
    private FormulaCompliance.Check check(Item fg, Item m, BigDecimal qtyPer) {
        return FormulaCompliance.check(m.getName(), m.getItemType() == ItemType.RM, regulatory.isBanned(m), regulatory.effectiveLimit(m),
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
