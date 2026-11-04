package com.cosmetic.scm.inventory;

import java.util.List;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.inventory.InventoryService.BatchView;
import com.cosmetic.scm.inventory.InventoryService.StockRow;
import com.cosmetic.scm.inventory.InventoryService.TxnView;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventory;

    @GetMapping("/stock")
    public List<StockRow> stock(@RequestParam(required = false) ItemType type,
                                @RequestParam(required = false) Long warehouseId,
                                @RequestParam(required = false) String q,
                                @RequestParam(defaultValue = "false") boolean includeZero,
                                @RequestParam(defaultValue = "5000") int limit) {
        // 页面列表设上限：不筛选时可能有几万行，全部发给浏览器会卡死。完整数据用导出
        List<StockRow> all = inventory.listStock(type, warehouseId, q, includeZero);
        return all.size() <= limit ? all : all.subList(0, Math.max(0, limit));
    }

    @GetMapping("/batches")
    public List<BatchView> batches(@RequestParam(required = false) List<BatchStatus> status,
                                   @RequestParam(required = false) String q) {
        return inventory.listBatches(status, q);
    }

    @GetMapping("/batches/{batchNo}/txns")
    public List<TxnView> txns(@PathVariable String batchNo) {
        return inventory.txnsOfBatch(inventory.batchByNo(batchNo).getId());
    }
}
