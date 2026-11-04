package com.cosmetic.scm.qms;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.RetainStatus;
import com.cosmetic.scm.domain.RetainTxnType;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.CurrentUser;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 留样台账。上市放行时自动入账，保存期 = 有效期 + 6 个月（GMP 第十八条）。
 * 投诉调查等可以领用；保存期满前不能销毁。
 */
@Service
@RequiredArgsConstructor
public class RetainService {

    private final RetainSampleRepository samples;
    private final RetainTxnRepository txns;
    private final BatchRepository batches;
    private final MasterLookup lookup;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record UseReq(@NotNull @Min(1) Integer qty,
                         @NotBlank(message = "请填写领用原因") @Size(max = 300) String reason) {
    }

    public record DestroyReq(@NotBlank(message = "请填写销毁方式和监督人") @Size(max = 300) String reason,
                             @NotBlank(message = "请输入签批密码") String password) {
    }

    public record TxnView(Long id, RetainTxnType txnType, String txnTypeLabel, int qty, String reason, String doneBy, LocalDateTime doneAt) {
    }

    /** expired：保存期已满，可以销毁。 */
    public record SampleView(Long id, Long batchId, String batchNo, String itemName, int qty, int remainingQty, String location,
                             LocalDate retainUntil, boolean expired, RetainStatus status, String statusLabel, List<TxnView> txns) {
    }

    @Transactional(readOnly = true)
    public List<SampleView> list() {
        return toViews(samples.findTop300ByOrderByRetainUntilAsc());
    }

    /** 全部留样，导出用。 */
    @Transactional(readOnly = true)
    public List<SampleView> listAll() {
        return toViews(samples.findAllByOrderByRetainUntilAsc());
    }

    /** 保存期已满、还没销毁的留样。 */
    @Transactional(readOnly = true)
    public List<SampleView> dueForDestruction() {
        LocalDate today = time.today();
        return toViews(samples.findByStatus(RetainStatus.HELD).stream().filter(s -> s.getRetainUntil().isBefore(today)).toList());
    }

    @Transactional
    public SampleView use(Long id, UseReq req) {
        RetainSample s = require(id);
        if (s.getStatus() != RetainStatus.HELD) {
            throw new BizException("这份留样当前为" + s.getStatus().getLabel());
        }
        if (req.qty() > s.getRemainingQty()) {
            throw new BizException("留样只剩 " + s.getRemainingQty() + " 件");
        }
        s.setRemainingQty(s.getRemainingQty() - req.qty());
        if (s.getRemainingQty() == 0) {
            s.setStatus(RetainStatus.USED_UP);
        }
        samples.save(s);
        record(s, RetainTxnType.USE, req.qty(), req.reason(), currentUser.usernameOrSystem());
        audit.log("领用留样", batchNo(s), req.qty() + " 件，剩余 " + s.getRemainingQty() + " 件", req.reason().trim());
        return toViews(List.of(s)).get(0);
    }

    @Transactional
    public SampleView destroy(Long id, DestroyReq req, AppUser signer) {
        RetainSample s = require(id);
        if (s.getStatus() != RetainStatus.HELD) {
            throw new BizException("这份留样当前为" + s.getStatus().getLabel() + "，不能销毁");
        }
        if (!s.getRetainUntil().isBefore(time.today())) {
            throw new BizException("留样要保存到 " + s.getRetainUntil() + "，保存期满后才能销毁");
        }
        int qty = s.getRemainingQty();
        s.setRemainingQty(0);
        s.setStatus(RetainStatus.DESTROYED);
        samples.save(s);
        record(s, RetainTxnType.DESTROY, qty, req.reason(), signer.getDisplayName());
        audit.log("销毁留样", batchNo(s), qty + " 件，签批人 " + signer.getDisplayName(), req.reason().trim());
        return toViews(List.of(s)).get(0);
    }

    private void record(RetainSample s, RetainTxnType type, int qty, String reason, String by) {
        RetainTxn t = new RetainTxn();
        t.setSampleId(s.getId());
        t.setTxnType(type);
        t.setQty(qty);
        t.setReason(reason.trim());
        t.setDoneBy(by);
        t.setDoneAt(time.now());
        txns.save(t);
    }

    private String batchNo(RetainSample s) {
        return batches.findById(s.getBatchId()).map(Batch::getBatchNo).orElse("—");
    }

    private RetainSample require(Long id) {
        return samples.lockById(id).orElseThrow(() -> BizException.notFound("留样记录"));
    }

    private List<SampleView> toViews(List<RetainSample> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, Batch> bm = batches.findAllById(list.stream().map(RetainSample::getBatchId).toList())
                .stream().collect(Collectors.toMap(Batch::getId, Function.identity()));
        Map<Long, List<RetainTxn>> tm = txns.findBySampleIdInOrderByIdAsc(list.stream().map(RetainSample::getId).toList())
                .stream().collect(Collectors.groupingBy(RetainTxn::getSampleId));
        LocalDate today = time.today();
        return list.stream().map(s -> {
            Batch b = bm.get(s.getBatchId());
            return new SampleView(s.getId(), s.getBatchId(), b.getBatchNo(), lookup.item(b.getItemId()).getName(), s.getQty(),
                    s.getRemainingQty(), s.getLocation(), s.getRetainUntil(), s.getRetainUntil().isBefore(today), s.getStatus(),
                    s.getStatus().getLabel(), tm.getOrDefault(s.getId(), List.of()).stream()
                    .map(t -> new TxnView(t.getId(), t.getTxnType(), t.getTxnType().getLabel(), t.getQty(), t.getReason(), t.getDoneBy(), t.getDoneAt()))
                    .toList());
        }).toList();
    }
}
