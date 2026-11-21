package com.cosmetic.scm.recall;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.complaint.Complaint;
import com.cosmetic.scm.complaint.ComplaintRepository;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.CaseStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.InventoryService;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.sales.SalesOrder;
import com.cosmetic.scm.sales.SalesOrderRepository;
import com.cosmetic.scm.sales.SoAllocation;
import com.cosmetic.scm.sales.SoAllocationRepository;
import com.cosmetic.scm.salesreturn.SalesReturn;
import com.cosmetic.scm.salesreturn.SalesReturnLine;
import com.cosmetic.scm.salesreturn.SalesReturnLineRepository;
import com.cosmetic.scm.salesreturn.SalesReturnRepository;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.CurrentUser;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 产品召回（《化妆品监督管理条例》第四十四条）。由质量安全负责人发起：
 * 同一事务内冻结批次、按出库记录为每个收货客户生成通知；
 * 客户退回的货登记为“召回回收”的销售退货，计入回收进度；全部客户通知到位后才能关闭。
 */
@Service
@RequiredArgsConstructor
public class RecallService {

    private final RecallRepository recalls;
    private final RecallNoticeRepository notices;
    private final ComplaintRepository complaints;
    private final BatchRepository batches;
    private final SoAllocationRepository allocations;
    private final SalesOrderRepository salesOrders;
    private final SalesReturnRepository returns;
    private final SalesReturnLineRepository returnLines;
    private final InventoryService inventory;
    private final MasterLookup lookup;
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record RecallReq(@NotBlank(message = "请填写批号") String batchNo,
                            Long complaintId,
                            @NotBlank(message = "请填写召回原因") @Size(max = 500) String reason,
                            @NotBlank(message = "请输入签批密码") String password) {
    }

    public record NotifyReq(@Size(max = 300) String note) {
    }

    public record CloseReq(@NotBlank(message = "请填写召回总结") @Size(max = 1000) String conclusion,
                           @NotBlank(message = "请输入签批密码") String password) {
    }

    public record NoticeView(Long id, Long customerId, String customerName, String channel, String contact,
                             BigDecimal shippedQty, BigDecimal recoveredQty, LocalDateTime notifiedAt,
                             String notifiedBy, String note,
                             BigDecimal returnedQty) {
    }

    public record RecallView(Long id, String recallNo, Long batchId, String batchNo, String itemName, String unit,
                             String batchStatusLabel, Long complaintId, String complaintNo, String reason,
                             CaseStatus status, String statusLabel, BigDecimal shippedQty, BigDecimal recoveredQty,
                             long notifiedCount, String conclusion, String openedByName, LocalDateTime openedAt,
                             String closedByName, LocalDateTime closedAt, List<NoticeView> notices,
                             BigDecimal returnedQty, boolean openingBatch) {
    }

    @Transactional(readOnly = true)
    public List<RecallView> list() {
        return toViews(recalls.findTop300ByOrderByIdDesc());
    }

    @Transactional(readOnly = true)
    public List<RecallView> listAll() {
        return toViews(recalls.findAllByOrderByIdDesc());
    }

    @Transactional(readOnly = true)
    public List<RecallView> open() {
        return toViews(recalls.findByStatus(CaseStatus.OPEN));
    }

    @Transactional
    public RecallView create(RecallReq req, AppUser signer) {
        // 先锁批次再查“是否已有进行中的召回”，否则同时发起会开出多个召回单。必须是事务里第一句：
        // MySQL 可重复读下，锁之前有过普通查询的话，后面的查询读不到别人刚提交的召回
        Batch b = batches.lockByBatchNo(req.batchNo() == null ? null : req.batchNo().trim())
                .orElseThrow(() -> BizException.notFound("批号 " + req.batchNo() + " "));
        Item item = lookup.item(b.getItemId());
        if (item.getItemType() != ItemType.FG) {
            throw new BizException("召回针对成品批次；物料问题请在批次追溯里冻结受影响批次");
        }
        if (recalls.existsByBatchIdAndStatus(b.getId(), CaseStatus.OPEN)) {
            throw new BizException("批次 " + b.getBatchNo() + " 已有进行中的召回");
        }
        Map<Long, BigDecimal> shippedByCustomer = shippedByCustomer(b.getId());
        // 期初批次上线前可能已经发出去了，发货记录不在系统里，不能因此拒绝召回
        if (shippedByCustomer.isEmpty() && b.getSourceType() != com.cosmetic.scm.domain.SourceType.OPEN) {
            throw new BizException("批次 " + b.getBatchNo() + " 还没有出库记录，在库冻结即可，无需召回");
        }
        Complaint complaint = null;
        if (req.complaintId() != null) {
            complaint = complaints.lockById(req.complaintId()).orElseThrow(() -> BizException.notFound("投诉"));
            if (complaint.getRecallId() != null) {
                throw new BizException("投诉 " + complaint.getComplaintNo() + " 已关联召回");
            }
            if (complaint.getBatchId() != null && !complaint.getBatchId().equals(b.getId())) {
                throw new BizException("投诉 " + complaint.getComplaintNo() + " 登记的批号与召回批号不一致");
            }
        }

        Recall r = new Recall();
        r.setRecallNo(docNo.next("RC", time.today()));
        r.setBatchId(b.getId());
        r.setComplaintId(complaint == null ? null : complaint.getId());
        r.setReason(req.reason().trim());
        r.setStatus(CaseStatus.OPEN);
        r.setShippedQty(shippedByCustomer.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        r.setOpenedByName(signer.getDisplayName());
        r.setOpenedAt(time.now());
        recalls.save(r);
        shippedByCustomer.forEach((customerId, qty) -> {
            RecallNotice n = new RecallNotice();
            n.setRecallId(r.getId());
            n.setCustomerId(customerId);
            n.setShippedQty(qty);
            notices.save(n);
        });
        if (complaint != null) {
            complaint.setRecallId(r.getId());
            if (complaint.getBatchId() == null) {
                complaint.setBatchId(b.getId());
            }
        }

        // 冻结在库部分，防止继续出库；已冻结或不合格的保持原状
        String frozen = "";
        if (b.getStatus() != BatchStatus.FROZEN && b.getStatus() != BatchStatus.REJECTED) {
            b.setStatusBeforeFreeze(b.getStatus());
            b.setStatus(BatchStatus.FROZEN);
            b.setFreezeReason("召回 " + r.getRecallNo() + "：" + r.getReason());
            batches.save(b);
            frozen = "，批次已冻结";
        }
        audit.log("发起召回", r.getRecallNo(), item.getName() + " " + b.getBatchNo() + "，涉及 " + shippedByCustomer.size()
                + " 个客户共 " + plain(r.getShippedQty()) + frozen + "，签批人 " + signer.getDisplayName(), r.getReason());
        return single(r);
    }

    @Transactional
    public RecallView notify(Long noticeId, NotifyReq req) {
        RecallNotice n = notices.lockById(noticeId).orElseThrow(() -> BizException.notFound("召回通知"));
        Recall r = recall(n.getRecallId());
        if (r.getStatus() != CaseStatus.OPEN) {
            throw new BizException("召回 " + r.getRecallNo() + " 已关闭");
        }
        if (n.getNotifiedAt() != null) {
            throw new BizException("该客户已于 " + n.getNotifiedAt().toLocalDate() + " 通知");
        }
        n.setNotifiedAt(time.now());
        n.setNotifiedBy(currentUser.usernameOrSystem());
        n.setNote(req == null || req.note() == null || req.note().isBlank() ? null : req.note().trim());
        notices.save(n);
        audit.log("召回通知客户", r.getRecallNo(), lookup.partner(n.getCustomerId()).getName(), n.getNote());
        return single(r);
    }

    @Transactional
    public RecallView close(Long id, CloseReq req, AppUser signer) {
        Recall r = recall(id);
        if (r.getStatus() != CaseStatus.OPEN) {
            throw new BizException("召回 " + r.getRecallNo() + " 已关闭");
        }
        RecallView v = single(r);
        List<String> pending = v.notices().stream().filter(n -> n.notifiedAt() == null).map(NoticeView::customerName).toList();
        if (!pending.isEmpty()) {
            throw new BizException("还有客户未通知：" + String.join("、", pending));
        }
        r.setStatus(CaseStatus.CLOSED);
        r.setConclusion(req.conclusion().trim());
        r.setClosedByName(signer.getDisplayName());
        r.setClosedAt(time.now());
        recalls.save(r);
        audit.log("关闭召回", r.getRecallNo(), "已回收 " + plain(v.recoveredQty()) + " / 在外 " + plain(v.shippedQty().subtract(v.returnedQty()))
                + "，签批人 " + signer.getDisplayName(), r.getConclusion());
        return single(r);
    }

    private Recall recall(Long id) {
        return recalls.lockById(id).orElseThrow(() -> BizException.notFound("召回"));
    }

    /** 批次按客户汇总的出库量，保持出库先后顺序。 */
    private Map<Long, BigDecimal> shippedByCustomer(Long batchId) {
        List<SoAllocation> allocs = allocations.findByBatchIdIn(List.of(batchId));
        Map<Long, SalesOrder> sos = salesOrders.findAllById(allocs.stream().map(SoAllocation::getSoId).distinct().toList())
                .stream().collect(Collectors.toMap(SalesOrder::getId, Function.identity()));
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        allocs.stream().sorted((a, c) -> a.getId().compareTo(c.getId()))
                .forEach(a -> out.merge(sos.get(a.getSoId()).getCustomerId(), a.getQty(), BigDecimal::add));
        return out;
    }

    private RecallView single(Recall r) {
        return toViews(List.of(r)).get(0);
    }

    private List<RecallView> toViews(List<Recall> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<Long> ids = list.stream().map(Recall::getId).toList();
        Map<Long, List<RecallNotice>> nm = notices.findByRecallIdIn(ids).stream()
                .sorted((a, c) -> a.getId().compareTo(c.getId()))
                .collect(Collectors.groupingBy(RecallNotice::getRecallId));
        Map<Long, Batch> bm = batches.findAllById(list.stream().map(Recall::getBatchId).distinct().toList()).stream()
                .collect(Collectors.toMap(Batch::getId, Function.identity()));
        Map<Long, String> complaintNos = complaints.findAllById(list.stream().map(Recall::getComplaintId)
                        .filter(java.util.Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(Complaint::getId, Complaint::getComplaintNo));
        Map<Long, Item> im = lookup.itemMap();
        Map<Long, Partner> pm = lookup.partnerMap();

        List<RecallView> out = new ArrayList<>();
        for (Recall r : list) {
            Batch b = bm.get(r.getBatchId());
            Item i = im.get(b.getItemId());
            Map<Long, BigDecimal> recovered = recoveredByCustomer(r);
            Map<Long, BigDecimal> returned = returnedOutsideRecall(r);
            List<NoticeView> nv = nm.getOrDefault(r.getId(), List.of()).stream().map(n -> {
                Partner c = pm.get(n.getCustomerId());
                return new NoticeView(n.getId(), c.getId(), c.getName(), c.getChannel(), c.getContact(), n.getShippedQty(),
                        recovered.getOrDefault(c.getId(), BigDecimal.ZERO), n.getNotifiedAt(), n.getNotifiedBy(), n.getNote(),
                        returned.getOrDefault(c.getId(), BigDecimal.ZERO));
            }).toList();
            BigDecimal recoveredTotal = recovered.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            out.add(new RecallView(r.getId(), r.getRecallNo(), b.getId(), b.getBatchNo(), i.getName(), i.getUnit(),
                    b.getStatus().getLabel(), r.getComplaintId(), complaintNos.get(r.getComplaintId()), r.getReason(),
                    r.getStatus(), r.getStatus().getLabel(), r.getShippedQty(), recoveredTotal,
                    nv.stream().filter(n -> n.notifiedAt() != null).count(), r.getConclusion(), r.getOpenedByName(),
                    r.getOpenedAt(), r.getClosedByName(), r.getClosedAt(), nv,
                    returned.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add),
                    b.getSourceType() == com.cosmetic.scm.domain.SourceType.OPEN));
        }
        return out;
    }

    /** 不是这次召回回收的退货（普通退货、转可售后可能再发给别人）。发出量减去它才是还在外面的量，否则回收率永远到不了 100%。 */
    private Map<Long, BigDecimal> returnedOutsideRecall(Recall r) {
        List<SalesReturnLine> lines = returnLines.findByBatchIdIn(List.of(r.getBatchId()));
        if (lines.isEmpty()) {
            return Map.of();
        }
        Map<Long, SalesReturn> rm = returns.findAllById(lines.stream().map(SalesReturnLine::getReturnId).distinct().toList()).stream()
                .collect(Collectors.toMap(SalesReturn::getId, Function.identity()));
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        for (SalesReturnLine l : lines) {
            SalesReturn sr = rm.get(l.getReturnId());
            if (!r.getId().equals(sr.getRecallId())) {
                out.merge(sr.getCustomerId(), l.getQty(), BigDecimal::add);
            }
        }
        return out;
    }

    private Map<Long, BigDecimal> recoveredByCustomer(Recall r) {
        List<SalesReturn> rs = returns.findByRecallId(r.getId());
        if (rs.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> customerOf = rs.stream().collect(Collectors.toMap(SalesReturn::getId, SalesReturn::getCustomerId));
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        for (SalesReturnLine l : returnLines.findByReturnIdIn(customerOf.keySet())) {
            if (l.getBatchId().equals(r.getBatchId())) {
                out.merge(customerOf.get(l.getReturnId()), l.getQty(), BigDecimal::add);
            }
        }
        return out;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
