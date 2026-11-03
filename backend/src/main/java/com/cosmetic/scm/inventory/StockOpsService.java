package com.cosmetic.scm.inventory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.InvDocType;
import com.cosmetic.scm.domain.TxnType;
import com.cosmetic.scm.domain.WarehouseKind;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Warehouse;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.CurrentUser;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存作业：仓间调拨、盘点调整、报废出库。
 * 数量变化全部通过 InventoryService，单据、流水、审计在同一事务内写入。
 * 退货隔离区的货只能走退货处置，这里一律不接受。
 */
@Service
@RequiredArgsConstructor
public class StockOpsService {

    private final InvDocRepository docs;
    private final InvDocLineRepository docLines;
    private final BatchRepository batches;
    private final InventoryService inventory;
    private final MasterLookup lookup;
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record QtyLineReq(@NotNull Long batchId, @NotNull @Positive BigDecimal qty) {
    }

    public record CountLineReq(@NotNull Long batchId, @NotNull @PositiveOrZero BigDecimal countedQty) {
    }

    public record TransferReq(@NotNull Long fromWarehouseId, @NotNull Long toWarehouseId,
                              @NotBlank(message = "请填写调拨原因") @Size(max = 300) String reason,
                              @NotEmpty(message = "至少一行") @Valid List<QtyLineReq> lines) {
    }

    public record AdjustReq(@NotNull Long warehouseId,
                            @NotBlank(message = "请填写盘点说明") @Size(max = 300) String reason,
                            @NotEmpty(message = "至少一行") @Valid List<CountLineReq> lines) {
    }

    public record ScrapReq(@NotNull Long warehouseId,
                           @NotBlank(message = "请填写报废原因") @Size(max = 300) String reason,
                           @NotEmpty(message = "至少一行") @Valid List<QtyLineReq> lines,
                           @NotBlank(message = "请输入签批密码") String password) {
    }

    public record DocLineView(Long batchId, String batchNo, String itemCode, String itemName, String unit,
                              BigDecimal bookQty, BigDecimal qty) {
    }

    public record DocView(Long id, String docNo, InvDocType docType, String docTypeLabel, Long fromWarehouseId,
                          String fromWarehouseName, Long toWarehouseId, String toWarehouseName, String reason,
                          String signedByName, String createdBy, LocalDateTime createdAt, List<DocLineView> lines) {
    }

    @Transactional(readOnly = true)
    public List<DocView> list() {
        return toViews(docs.findTop300ByOrderByIdDesc());
    }

    @Transactional
    public DocView transfer(TransferReq req) {
        Warehouse from = usable(req.fromWarehouseId());
        Warehouse to = usable(req.toWarehouseId());
        if (from.getId().equals(to.getId())) {
            throw new BizException("调出仓和调入仓不能相同");
        }
        InvDoc doc = newDoc("TR", InvDocType.TRANSFER, from, to, req.reason());
        List<String> detail = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (QtyLineReq l : req.lines()) {
            Batch b = distinctBatch(l.batchId(), seen);
            inventory.change(b, from.getId(), l.qty().negate(), TxnType.TRANSFER, doc.getDocNo());
            inventory.change(b, to.getId(), l.qty(), TxnType.TRANSFER, doc.getDocNo());
            saveLine(doc, b, null, l.qty());
            detail.add(b.getBatchNo() + "×" + plain(l.qty()));
        }
        audit.log("仓间调拨", doc.getDocNo(), from.getName() + " → " + to.getName() + "：" + String.join("，", detail),
                doc.getReason());
        return single(doc);
    }

    @Transactional
    public DocView adjust(AdjustReq req) {
        Warehouse wh = usable(req.warehouseId());
        InvDoc doc = newDoc("AD", InvDocType.ADJUST, wh, null, req.reason());
        List<String> detail = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (CountLineReq l : req.lines()) {
            Batch b = distinctBatch(l.batchId(), seen);
            BigDecimal delta = inventory.adjustTo(b, wh.getId(), l.countedQty(), doc.getDocNo());
            if (delta.signum() != 0) {
                saveLine(doc, b, l.countedQty().subtract(delta), delta);
                detail.add(b.getBatchNo() + (delta.signum() > 0 ? " 盘盈 " : " 盘亏 ") + plain(delta.abs()));
            }
        }
        if (detail.isEmpty()) {
            throw new BizException("实盘数量与账面一致，无需调整");
        }
        audit.log("盘点调整", doc.getDocNo(), wh.getName() + "：" + String.join("，", detail), doc.getReason());
        return single(doc);
    }

    /** 报废是质量决定，须质量人员再次输入密码签批。 */
    @Transactional
    public DocView scrap(ScrapReq req, AppUser signer) {
        Warehouse wh = usable(req.warehouseId());
        InvDoc doc = newDoc("SC", InvDocType.SCRAP, wh, null, req.reason());
        doc.setSignedByName(signer.getDisplayName());
        List<String> detail = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (QtyLineReq l : req.lines()) {
            Batch b = distinctBatch(l.batchId(), seen);
            inventory.change(b, wh.getId(), l.qty().negate(), TxnType.SCRAP, doc.getDocNo());
            saveLine(doc, b, null, l.qty());
            detail.add(b.getBatchNo() + "×" + plain(l.qty()));
        }
        audit.log("报废出库", doc.getDocNo(), wh.getName() + "：" + String.join("，", detail) + "，签批人 "
                + signer.getDisplayName(), doc.getReason());
        return single(doc);
    }

    private Warehouse usable(Long warehouseId) {
        Warehouse w = lookup.warehouse(warehouseId);
        if (w.getKind() == WarehouseKind.RETURN) {
            throw new BizException(w.getName() + " 的货请在“销售退货”里逐行处置");
        }
        return w;
    }

    private Batch distinctBatch(Long batchId, Set<Long> seen) {
        Batch b = inventory.batch(batchId);
        if (!seen.add(b.getId())) {
            throw new BizException("批次 " + b.getBatchNo() + " 重复，请合并为一行");
        }
        return b;
    }

    private InvDoc newDoc(String prefix, InvDocType type, Warehouse from, Warehouse to, String reason) {
        InvDoc doc = new InvDoc();
        doc.setDocNo(docNo.next(prefix, time.today()));
        doc.setDocType(type);
        doc.setFromWhId(from.getId());
        doc.setToWhId(to == null ? null : to.getId());
        doc.setReason(reason.trim());
        doc.setCreatedBy(currentUser.usernameOrSystem());
        doc.setCreatedAt(time.now());
        return docs.save(doc);
    }

    private void saveLine(InvDoc doc, Batch b, BigDecimal bookQty, BigDecimal qty) {
        InvDocLine line = new InvDocLine();
        line.setDocId(doc.getId());
        line.setBatchId(b.getId());
        line.setItemId(b.getItemId());
        line.setBookQty(bookQty);
        line.setQty(qty);
        docLines.save(line);
    }

    private DocView single(InvDoc doc) {
        return toViews(List.of(doc)).get(0);
    }

    private List<DocView> toViews(List<InvDoc> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<InvDocLine> lines = docLines.findByDocIdIn(list.stream().map(InvDoc::getId).toList());
        Map<Long, List<InvDocLine>> lm = lines.stream().collect(Collectors.groupingBy(InvDocLine::getDocId));
        Map<Long, Batch> bm = batches.findAllById(lines.stream().map(InvDocLine::getBatchId).distinct().toList())
                .stream().collect(Collectors.toMap(Batch::getId, Function.identity()));
        Map<Long, Item> im = lookup.itemMap();
        Map<Long, Warehouse> wm = lookup.warehouseMap();
        return list.stream().map(d -> {
            Warehouse from = wm.get(d.getFromWhId());
            Warehouse to = d.getToWhId() == null ? null : wm.get(d.getToWhId());
            List<DocLineView> lv = lm.getOrDefault(d.getId(), List.of()).stream().map(l -> {
                Item i = im.get(l.getItemId());
                return new DocLineView(l.getBatchId(), bm.get(l.getBatchId()).getBatchNo(), i.getCode(), i.getName(),
                        i.getUnit(), l.getBookQty(), l.getQty());
            }).toList();
            return new DocView(d.getId(), d.getDocNo(), d.getDocType(), d.getDocType().getLabel(), from.getId(),
                    from.getName(), to == null ? null : to.getId(), to == null ? null : to.getName(), d.getReason(),
                    d.getSignedByName(), d.getCreatedBy(), d.getCreatedAt(), lv);
        }).toList();
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
