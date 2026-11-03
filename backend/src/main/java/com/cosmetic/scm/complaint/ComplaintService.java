package com.cosmetic.scm.complaint;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.CaseStatus;
import com.cosmetic.scm.domain.ComplaintCategory;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.Severity;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.InventoryService;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.recall.Recall;
import com.cosmetic.scm.recall.RecallRepository;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.CurrentUser;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 投诉登记与处理。不良反应类投诉按《化妆品不良反应监测管理办法》计算上报时限：
 * 严重不良反应 7 日内、一般不良反应 30 日内；未登记上报不能关闭。
 * 关闭须质量人员签批，写明调查结论和纠正预防措施（CAPA）。
 */
@Service
@RequiredArgsConstructor
public class ComplaintService {

    public static final int SERIOUS_REPORT_DAYS = 7;
    public static final int NORMAL_REPORT_DAYS = 30;

    private final ComplaintRepository complaints;
    private final RecallRepository recalls;
    private final BatchRepository batches;
    private final InventoryService inventory;
    private final MasterLookup lookup;
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record ComplaintReq(@NotNull(message = "请选择受理日期") LocalDate receivedDate,
                               @NotBlank(message = "请填写来源渠道") @Size(max = 40) String channel,
                               Long customerId,
                               @Size(max = 100) String consumer,
                               @NotNull(message = "请选择产品") Long itemId,
                               @Size(max = 40) String batchNo,
                               @NotNull ComplaintCategory category,
                               @NotNull Severity severity,
                               @NotBlank(message = "请填写投诉内容") @Size(max = 1000) String description) {
    }

    public record ReportReq(@NotBlank(message = "请填写监测报告编号") @Size(max = 60) String reportNo,
                            @NotNull(message = "请选择上报日期") LocalDate reportedDate) {
    }

    public record CloseReq(@NotBlank(message = "请填写调查结论") @Size(max = 1000) String conclusion,
                           @Size(max = 1000) String capa,
                           @NotBlank(message = "请输入签批密码") String password) {
    }

    /** reportState: NONE（非不良反应）/ DONE / PENDING / OVERDUE */
    public record ComplaintView(Long id, String complaintNo, LocalDate receivedDate, String channel, Long customerId,
                                String customerName, String consumer, Long itemId, String itemName, Long batchId,
                                String batchNo, ComplaintCategory category, String categoryLabel, Severity severity,
                                String severityLabel, String description, CaseStatus status, String statusLabel,
                                String reportState, LocalDate reportDeadline, Long reportDaysLeft, String reportNo,
                                LocalDate reportedDate, String conclusion, String capa, Long recallId, String recallNo,
                                String closedBy, LocalDateTime closedAt, String createdBy, LocalDateTime createdAt) {
    }

    @Transactional(readOnly = true)
    public List<ComplaintView> list() {
        return toViews(complaints.findTop300ByOrderByIdDesc());
    }

    @Transactional(readOnly = true)
    public List<ComplaintView> open() {
        return toViews(complaints.findByStatus(CaseStatus.OPEN));
    }

    @Transactional
    public ComplaintView create(ComplaintReq req) {
        LocalDate today = time.today();
        if (req.receivedDate().isAfter(today)) {
            throw new BizException("受理日期不能晚于今天");
        }
        Item item = lookup.item(req.itemId());
        if (item.getItemType() != ItemType.FG) {
            throw new BizException("投诉针对成品，" + item.getName() + " 不是成品");
        }
        Long customerId = null;
        if (req.customerId() != null) {
            Partner c = lookup.partner(req.customerId());
            if (c.getKind() != PartnerKind.CUSTOMER) {
                throw new BizException(c.getName() + " 不是客户渠道");
            }
            customerId = c.getId();
        }
        Batch batch = null;
        if (req.batchNo() != null && !req.batchNo().isBlank()) {
            batch = inventory.batchByNo(req.batchNo());
            if (!batch.getItemId().equals(item.getId())) {
                throw new BizException("批号 " + batch.getBatchNo() + " 不是 " + item.getName() + " 的批次");
            }
        }
        Complaint c = new Complaint();
        c.setComplaintNo(docNo.next("CP", today));
        c.setReceivedDate(req.receivedDate());
        c.setChannel(req.channel().trim());
        c.setCustomerId(customerId);
        c.setConsumer(blankToNull(req.consumer()));
        c.setItemId(item.getId());
        c.setBatchId(batch == null ? null : batch.getId());
        c.setCategory(req.category());
        c.setSeverity(req.severity());
        c.setDescription(req.description().trim());
        c.setStatus(CaseStatus.OPEN);
        c.setCreatedBy(currentUser.usernameOrSystem());
        c.setCreatedAt(time.now());
        complaints.save(c);
        audit.log("登记投诉", c.getComplaintNo(), item.getName() + (batch == null ? "" : " " + batch.getBatchNo()) + "，"
                + req.category().getLabel() + "（" + req.severity().getLabel() + "）", c.getDescription());
        return single(c);
    }

    @Transactional
    public ComplaintView report(Long id, ReportReq req) {
        Complaint c = complaint(id);
        if (c.getCategory() != ComplaintCategory.ADVERSE) {
            throw new BizException("只有不良反应类投诉需要监测上报");
        }
        if (c.getStatus() != CaseStatus.OPEN) {
            throw new BizException("投诉 " + c.getComplaintNo() + " 已关闭");
        }
        if (req.reportedDate().isBefore(c.getReceivedDate()) || req.reportedDate().isAfter(time.today())) {
            throw new BizException("上报日期应在受理日期 " + c.getReceivedDate() + " 到今天之间");
        }
        c.setReportNo(req.reportNo().trim());
        c.setReportedDate(req.reportedDate());
        complaints.save(c);
        audit.log("不良反应上报", c.getComplaintNo(), "报告编号 " + c.getReportNo() + "，上报日期 " + c.getReportedDate());
        return single(c);
    }

    @Transactional
    public ComplaintView close(Long id, CloseReq req, AppUser signer) {
        Complaint c = complaint(id);
        if (c.getStatus() != CaseStatus.OPEN) {
            throw new BizException("投诉 " + c.getComplaintNo() + " 已关闭");
        }
        if (c.getCategory() == ComplaintCategory.ADVERSE && c.getReportedDate() == null) {
            throw new BizException("不良反应须先登记监测上报，才能关闭投诉");
        }
        c.setStatus(CaseStatus.CLOSED);
        c.setConclusion(req.conclusion().trim());
        c.setCapa(blankToNull(req.capa()));
        c.setClosedBy(signer.getDisplayName());
        c.setClosedAt(time.now());
        complaints.save(c);
        audit.log("关闭投诉", c.getComplaintNo(), "签批人 " + signer.getDisplayName()
                + (c.getCapa() == null ? "" : "；CAPA：" + c.getCapa()), c.getConclusion());
        return single(c);
    }

    /** 不良反应上报截止日；非不良反应返回 null。 */
    public static LocalDate reportDeadline(Complaint c) {
        if (c.getCategory() != ComplaintCategory.ADVERSE) {
            return null;
        }
        return c.getReceivedDate().plusDays(c.getSeverity() == Severity.SERIOUS ? SERIOUS_REPORT_DAYS : NORMAL_REPORT_DAYS);
    }

    private Complaint complaint(Long id) {
        return complaints.findById(id).orElseThrow(() -> BizException.notFound("投诉"));
    }

    private ComplaintView single(Complaint c) {
        return toViews(List.of(c)).get(0);
    }

    private List<ComplaintView> toViews(List<Complaint> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, Batch> bm = batches.findAllById(list.stream().map(Complaint::getBatchId)
                        .filter(java.util.Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(Batch::getId, Function.identity()));
        Map<Long, String> recallNos = recalls.findAllById(list.stream().map(Complaint::getRecallId)
                        .filter(java.util.Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(Recall::getId, Recall::getRecallNo));
        Map<Long, Item> im = lookup.itemMap();
        Map<Long, Partner> pm = lookup.partnerMap();
        LocalDate today = time.today();
        return list.stream().map(c -> {
            Partner cu = c.getCustomerId() == null ? null : pm.get(c.getCustomerId());
            Batch b = c.getBatchId() == null ? null : bm.get(c.getBatchId());
            LocalDate deadline = reportDeadline(c);
            String state;
            Long daysLeft = null;
            if (deadline == null) {
                state = "NONE";
            } else if (c.getReportedDate() != null) {
                state = "DONE";
            } else {
                daysLeft = ChronoUnit.DAYS.between(today, deadline);
                state = daysLeft < 0 ? "OVERDUE" : "PENDING";
            }
            return new ComplaintView(c.getId(), c.getComplaintNo(), c.getReceivedDate(), c.getChannel(), c.getCustomerId(),
                    cu == null ? null : cu.getName(), c.getConsumer(), c.getItemId(), im.get(c.getItemId()).getName(),
                    c.getBatchId(), b == null ? null : b.getBatchNo(), c.getCategory(), c.getCategory().getLabel(),
                    c.getSeverity(), c.getSeverity().getLabel(), c.getDescription(), c.getStatus(),
                    c.getStatus().getLabel(), state, deadline, daysLeft, c.getReportNo(), c.getReportedDate(),
                    c.getConclusion(), c.getCapa(), c.getRecallId(), recallNos.get(c.getRecallId()), c.getClosedBy(),
                    c.getClosedAt(), c.getCreatedBy(), c.getCreatedAt());
        }).toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
