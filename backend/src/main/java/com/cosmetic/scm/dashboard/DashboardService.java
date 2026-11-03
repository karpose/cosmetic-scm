package com.cosmetic.scm.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.audit.AuditLog;
import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.MoStatus;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.PoStatus;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.InventoryService;
import com.cosmetic.scm.inventory.InventoryService.BatchView;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.master.MasterDtos.PartnerView;
import com.cosmetic.scm.production.MfgOrder;
import com.cosmetic.scm.production.MfgOrderRepository;
import com.cosmetic.scm.purchase.PurchaseOrderRepository;
import com.cosmetic.scm.sales.SalesOrderRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 总览：主线各环节数量 + 待办 + 最近操作。 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** 成品剩余效期低于此比例时列为临期。 */
    private static final double NEAR_EXPIRY_RATIO = 0.5;

    private final PurchaseOrderRepository purchaseOrders;
    private final BatchRepository batches;
    private final MfgOrderRepository mfgOrders;
    private final SalesOrderRepository salesOrders;
    private final InventoryService inventory;
    private final MasterService master;
    private final MasterLookup lookup;
    private final AuditService audit;
    private final TimeProvider time;

    public record Counts(long openPurchaseOrders, long quarantine, long availableMaterialBatches,
                         long wipOrders, long pendingRelease, long shipped30d) {
    }

    /** kind: QC / RELEASE / MO_ISSUE / MO_COMPLETE / LICENSE / NEAR_EXPIRY；level: warn / info / bad / muted */
    public record Todo(String kind, String label, String level, String title, String ref, String detail, String route) {
    }

    public record Summary(Counts counts, List<Todo> todos, List<AuditLog> recent) {
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        LocalDate today = time.today();
        List<BatchView> all = inventory.listBatches(null, null);
        long availableMaterial = all.stream()
                .filter(b -> !"FG".equals(b.itemType()) && b.status() == BatchStatus.RELEASED && b.onHand().signum() > 0)
                .count();
        List<MfgOrder> wip = mfgOrders.findByStatusIn(List.of(MoStatus.PLANNED, MoStatus.ISSUED));
        Counts counts = new Counts(
                purchaseOrders.countByStatusIn(List.of(PoStatus.OPEN, PoStatus.PARTIAL)),
                batches.countByStatus(BatchStatus.QUARANTINE),
                availableMaterial,
                wip.size(),
                batches.countByStatus(BatchStatus.PENDING_RELEASE),
                salesOrders.countByOrderDateGreaterThanEqual(today.minusDays(30)));

        List<Todo> todos = new ArrayList<>();
        for (BatchView b : all) {
            if (b.status() == BatchStatus.QUARANTINE) {
                todos.add(new Todo("QC", "物料待检", "warn", b.itemName(), b.batchNo(),
                        plain(b.qtyIn()) + " " + b.unit() + " · " + nullToDash(b.partnerName()), "/quality"));
            }
        }
        for (BatchView b : all) {
            if (b.status() == BatchStatus.PENDING_RELEASE) {
                todos.add(new Todo("RELEASE", "待上市放行", "info", b.itemName(), b.batchNo(),
                        plain(b.qtyIn()) + " · " + nullToDash(b.partnerName()), "/quality"));
            }
        }
        Map<Long, Item> im = lookup.itemMap();
        for (MfgOrder m : wip) {
            boolean planned = m.getStatus() == MoStatus.PLANNED;
            todos.add(new Todo(planned ? "MO_ISSUE" : "MO_COMPLETE", planned ? "待发料" : "待完工", "muted",
                    im.get(m.getFgItemId()).getName() + " × " + plain(m.getPlannedQty()), m.getMoNo(),
                    "计划 " + m.getPlannedDate(), "/production"));
        }
        for (PartnerView p : master.listPartners()) {
            if (p.kind() == PartnerKind.CUSTOMER) {
                continue;
            }
            if ("EXPIRED".equals(p.licenseState())) {
                todos.add(new Todo("LICENSE", "资质已过期", "bad", p.name(), p.licenseNo(),
                        "已于 " + p.licenseExpiry() + " 过期，系统已停止向其下单", "/master"));
            } else if ("EXPIRING".equals(p.licenseState())) {
                todos.add(new Todo("LICENSE", "资质 " + p.licenseDaysLeft() + " 天后到期", "warn", p.name(), p.licenseNo(),
                        "有效期至 " + p.licenseExpiry(), "/master"));
            }
        }
        for (BatchView b : all) {
            if ("FG".equals(b.itemType()) && b.status() == BatchStatus.RELEASED && b.onHand().signum() > 0
                    && b.shelfRatio() != null && b.shelfRatio() < NEAR_EXPIRY_RATIO) {
                todos.add(new Todo("NEAR_EXPIRY", "成品临期", "bad", b.itemName(), b.batchNo(),
                        "剩余 " + plain(b.onHand()) + " · 效期至 " + b.expDate(), "/stock"));
            }
        }
        return new Summary(counts, todos, audit.list(null, 8));
    }

    private static String plain(BigDecimal v) {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString();
    }

    private static String nullToDash(String s) {
        return s == null ? "—" : s;
    }
}
