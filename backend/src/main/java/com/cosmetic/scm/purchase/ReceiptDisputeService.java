package com.cosmetic.scm.purchase;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.ShipmentStatus;
import com.cosmetic.scm.domain.SourceType;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.purchase.PurchaseService.ShipmentView;
import com.cosmetic.scm.security.CurrentUser;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReceiptDisputeService {

    private final PoShipmentRepository shipments;
    private final PoShipmentLineRepository shipmentLines;
    private final PurchaseOrderRepository orders;
    private final PoLineRepository lines;
    private final BatchRepository batches;
    private final MasterLookup lookup;
    private final CurrentUser currentUser;
    private final TimeProvider time;
    private final AuditService audit;

    public record ReportReq(@NotBlank(message = "请填写异议说明") @Size(max = 500) String note) {
    }

    public record ResolveReq(@NotBlank(message = "请填写处理结果") @Size(max = 500) String resolution) {
    }

    public record DisputeView(String note, LocalDateTime reportedAt, String resolution, LocalDateTime resolvedAt) {
    }

    public record ReceiptDifference(Long poLineId, String itemName, String unit, BigDecimal notifiedQty,
                                    BigDecimal receivedQty, boolean batchChanged) {
    }

    public record ReceivedLot(String itemName, String unit, String extBatchNo, BigDecimal qty) {
    }

    public record Details(String poNo, String supplierName, ShipmentView shipment,
                          List<ReceiptDifference> differences, List<ReceivedLot> received) {
    }

    @Transactional
    public DisputeView report(Long shipmentId, ReportReq req) {
        PoShipment shipment = lockShipment(shipmentId);
        PurchaseOrder po = orderOf(shipment);
        if (!currentUser.portalPartnerId().equals(po.getSupplierId())) {
            throw BizException.notFound("送货通知");
        }
        if (shipment.getReceiptDisputedAt() != null) {
            throw new BizException("这张送货通知已登记收货异议，请查看处理记录");
        }
        if (shipment.getStatus() != ShipmentStatus.RECEIVED || details(shipment, po).differences().isEmpty()) {
            throw new BizException("只有实收数量或批号有差异的送货通知可以提交异议");
        }
        shipment.setReceiptDisputeNote(req.note().strip());
        shipment.setReceiptDisputedAt(time.now());
        audit.log("供应商提交收货异议", shipment.getShipmentNo(), "采购单 " + po.getPoNo());
        return disputeView(shipment);
    }

    @Transactional(readOnly = true)
    public Details get(Long shipmentId) {
        PoShipment shipment = shipments.findById(shipmentId).filter(s -> s.getReceiptDisputedAt() != null)
                .orElseThrow(() -> BizException.notFound("收货异议"));
        return details(shipment, orderOf(shipment));
    }

    @Transactional
    public DisputeView resolve(Long shipmentId, ResolveReq req) {
        PoShipment shipment = lockShipment(shipmentId);
        if (shipment.getReceiptDisputedAt() == null) {
            throw BizException.notFound("收货异议");
        }
        if (shipment.getReceiptResolvedAt() != null) {
            throw new BizException("这张送货通知的收货异议已回复");
        }
        shipment.setReceiptResolutionNote(req.resolution().strip());
        shipment.setReceiptResolvedAt(time.now());
        audit.log("采购回复收货异议", shipment.getShipmentNo(), "采购单 " + orderOf(shipment).getPoNo());
        return disputeView(shipment);
    }

    static DisputeView disputeView(PoShipment shipment) {
        return shipment.getReceiptDisputedAt() == null ? null : new DisputeView(shipment.getReceiptDisputeNote(),
                shipment.getReceiptDisputedAt(), shipment.getReceiptResolutionNote(), shipment.getReceiptResolvedAt());
    }

    public static List<ReceiptDifference> compare(ShipmentView notice, List<Batch> received, Map<Long, PoLine> orderLines) {
        if (received.isEmpty()) {
            return List.of();
        }
        Map<Long, List<Batch>> byItem = received.stream().collect(Collectors.groupingBy(Batch::getItemId));
        List<ReceiptDifference> differences = new ArrayList<>();
        for (var line : notice.lines()) {
            List<Batch> actual = byItem.getOrDefault(orderLines.get(line.poLineId()).getItemId(), List.of());
            BigDecimal qty = actual.stream().map(Batch::getQtyIn).reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean batchChanged = actual.stream().anyMatch(b -> !line.extBatchNo().equals(b.getExtBatchNo()));
            if (qty.compareTo(line.qty()) != 0 || batchChanged) {
                differences.add(new ReceiptDifference(line.poLineId(), line.itemName(), line.unit(), line.qty(), qty, batchChanged));
            }
        }
        return differences;
    }

    private Details details(PoShipment shipment, PurchaseOrder po) {
        Map<Long, PoLine> orderLines = lines.findByPoIdOrderByLineNo(po.getId()).stream()
                .collect(Collectors.toMap(PoLine::getId, l -> l));
        Map<Long, Item> items = lookup.itemMap();
        ShipmentView notice = PurchaseService.shipmentView(shipment, shipmentLines.findByShipmentIdInOrderById(List.of(shipment.getId())),
                orderLines, items, true);
        List<Batch> received = batches.findByPoShipmentIdAndPartnerIdAndSourceTypeAndSourceNoOrderById(
                shipment.getId(), po.getSupplierId(), SourceType.PO, po.getPoNo());
        List<ReceivedLot> lots = received.stream().map(b -> {
            Item item = items.get(b.getItemId());
            return new ReceivedLot(item.getName(), item.getUnit(), b.getExtBatchNo(), b.getQtyIn());
        }).toList();
        return new Details(po.getPoNo(), lookup.partner(po.getSupplierId()).getName(), notice, compare(notice, received, orderLines), lots);
    }

    private PoShipment lockShipment(Long id) {
        return shipments.lockById(id).orElseThrow(() -> BizException.notFound("送货通知"));
    }

    private PurchaseOrder orderOf(PoShipment shipment) {
        return orders.findById(shipment.getPoId()).orElseThrow(() -> BizException.notFound("采购单"));
    }
}
