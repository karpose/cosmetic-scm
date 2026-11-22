package com.cosmetic.scm.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.WarehouseKind;
import com.cosmetic.scm.domain.rules.ShelfLife;
import com.cosmetic.scm.inventory.InventoryService.BatchView;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Warehouse;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 仓库扫码作业：扫批次标签查状态和库位；发料、出库时逐个扫码核对批次，核对结果记入审计日志，
 * 作为“实际发出的批次与系统分配一致”的证据。标签上的二维码内容就是批号。
 */
@Service
@RequiredArgsConstructor
public class ScanService {

    private final InventoryService inventory;
    private final MasterLookup lookup;
    private final AuditService audit;
    private final TimeProvider time;

    public record StockAt(String warehouseName, String warehouseKind, BigDecimal qty) {
    }

    /** level：ok 可以使用 / warn 可用但要注意 / bad 不能发料出库 */
    public record BatchCard(BatchView batch, List<StockAt> stock, String level, List<String> warnings) {
    }

    public record CheckReq(@NotNull @Pattern(regexp = "MO|SO", message = "核对类型只能是 MO（发料）或 SO（出库）") String refType,
                           @NotBlank @Size(max = 40) String refNo,
                           @NotNull List<@NotBlank String> expected,
                           @NotNull List<@NotBlank String> scanned) {
    }

    public record CheckResult(boolean passed, List<String> missing, List<String> unexpected) {
    }

    @Transactional(readOnly = true)
    public BatchCard card(String batchNo) {
        Batch b = inventory.batchByNo(batchNo);
        BatchView v = inventory.toViews(List.of(b)).get(0);
        Map<Long, Warehouse> wm = lookup.warehouseMap();
        List<StockAt> stock = inventory.stockOfBatch(b.getId()).stream().filter(s -> s.getQty().signum() > 0)
                .map(s -> new StockAt(wm.get(s.getWarehouseId()).getName(), wm.get(s.getWarehouseId()).getKind().name(), s.getQty()))
                .toList();
        List<String> warnings = new ArrayList<>();
        String level = "ok";
        LocalDate today = time.today();
        if (b.getStatus() != BatchStatus.RELEASED) {
            level = "bad";
            warnings.add("批次状态为“" + b.getStatus().getLabel() + "”，不能发料或出库"
                    + (b.getFreezeReason() == null ? "" : "（" + b.getFreezeReason() + "）"));
        }
        if (ShelfLife.isExpired(b.getExpDate(), today)) {
            level = "bad";
            warnings.add("已过有效期（" + b.getExpDate() + "）");
        } else if (v.shelfRatio() != null && v.shelfRatio() < 1.0 / 3) {
            level = "bad".equals(level) ? level : "warn";
            warnings.add("剩余效期不足 1/3，多数客户不接收");
        }
        if (stock.stream().anyMatch(s -> WarehouseKind.RETURN.name().equals(s.warehouseKind()))) {
            level = "bad".equals(level) ? level : "warn";
            warnings.add("有一部分在退货隔离区，待质量处置，不能直接发出");
        }
        if (stock.isEmpty()) {
            level = "bad".equals(level) ? level : "warn";
            warnings.add("系统里已无库存，实物与账面不符请盘点");
        }
        return new BatchCard(v, stock, level, warnings);
    }

    @Transactional
    public CheckResult recordCheck(CheckReq req) {
        Set<String> expected = new LinkedHashSet<>(req.expected().stream().map(String::trim).toList());
        Set<String> scanned = new LinkedHashSet<>(req.scanned().stream().map(String::trim).toList());
        List<String> missing = expected.stream().filter(x -> !scanned.contains(x)).toList();
        List<String> unexpected = scanned.stream().filter(x -> !expected.contains(x)).toList();
        boolean passed = missing.isEmpty() && unexpected.isEmpty();
        String what = "MO".equals(req.refType()) ? "发料扫码核对" : "出库扫码核对";
        audit.log(what, req.refNo(), (passed ? "一致" : "不一致") + "：应扫 " + String.join("、", expected) + "；已扫 "
                + String.join("、", scanned), passed ? null
                : (missing.isEmpty() ? "" : "漏扫 " + String.join("、", missing) + "；")
                + (unexpected.isEmpty() ? "" : "多扫 / 扫错 " + String.join("、", unexpected)));
        return new CheckResult(passed, missing, unexpected);
    }

    public record RecentCheck(Long id, String action, String refNo, boolean passed, String detail, String reason,
                              String operator, java.time.LocalDateTime checkedAt) {
    }

    @Transactional(readOnly = true)
    public List<RecentCheck> recentChecks() {
        return audit.list(null, 200).stream()
                .filter(a -> "发料扫码核对".equals(a.getAction()) || "出库扫码核对".equals(a.getAction()))
                .limit(20)
                .map(a -> new RecentCheck(
                        a.getId(),
                        a.getAction(),
                        a.getTarget(),
                        a.getDetail() != null && a.getDetail().startsWith("一致"),
                        a.getDetail(),
                        a.getReason(),
                        a.getDisplayName() != null ? a.getDisplayName() : a.getUsername(),
                        a.getCreatedAt()))
                .toList();
    }
}
