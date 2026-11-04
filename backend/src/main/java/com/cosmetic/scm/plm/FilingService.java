package com.cosmetic.scm.plm;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.FilingStatus;
import com.cosmetic.scm.domain.FilingType;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.ItemRepository;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.security.CurrentUser;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 备案 / 注册登记。完成时把编号写到物料上（上市放行检查的就是它）；
 * 特殊化妆品注册证有有效期，到期后不能再上市放行。
 */
@Service
@RequiredArgsConstructor
public class FilingService {

    /** 注册证到期前多少天开始提醒。 */
    public static final int EXPIRY_WARN_DAYS = 180;

    private final ProductFilingRepository filings;
    private final FormulaVersionRepository versions;
    private final ItemRepository items;
    private final MasterLookup lookup;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record FilingReq(@NotNull(message = "请选择成品") Long fgItemId,
                            @NotNull FilingType filingType,
                            Long formulaVersionId,
                            LocalDate submittedDate,
                            @Size(max = 300) String note) {
    }

    public record CompleteReq(@NotBlank(message = "请填写备案或注册编号") @Size(max = 60) String filingNo,
                              @NotNull(message = "请选择完成日期") LocalDate filedDate,
                              LocalDate expiryDate) {
    }

    public record WithdrawReq(@NotBlank(message = "请填写撤回原因") @Size(max = 300) String reason) {
    }

    public record FilingView(Long id, Long fgItemId, String fgCode, String fgName, Long formulaVersionId, Integer versionNo,
                             FilingType filingType, String filingTypeLabel, String filingNo, FilingStatus status,
                             String statusLabel, LocalDate submittedDate, LocalDate filedDate, LocalDate expiryDate,
                             Long expiryDaysLeft, String note, String createdBy, LocalDateTime createdAt) {
    }

    @Transactional(readOnly = true)
    public List<FilingView> list() {
        return filings.findTop300ByOrderByIdDesc().stream().map(this::toView).toList();
    }

    /** 已完成、有有效期、将在提醒窗口内到期（含已过期）的注册证。 */
    @Transactional(readOnly = true)
    public List<FilingView> expiring() {
        LocalDate limit = time.today().plusDays(EXPIRY_WARN_DAYS);
        return filings.findByStatus(FilingStatus.FILED).stream()
                .filter(f -> f.getExpiryDate() != null && !f.getExpiryDate().isAfter(limit))
                .map(this::toView).toList();
    }

    @Transactional
    public FilingView create(FilingReq req) {
        Item fg = lookup.item(req.fgItemId());
        if (fg.getItemType() != ItemType.FG) {
            throw new BizException(fg.getName() + " 不是成品，不需要备案");
        }
        if (req.formulaVersionId() != null) {
            FormulaVersion v = versions.findById(req.formulaVersionId()).orElseThrow(() -> BizException.notFound("配方版本"));
            if (!v.getFgItemId().equals(fg.getId())) {
                throw new BizException("V" + v.getVersionNo() + " 不是 " + fg.getName() + " 的配方版本");
            }
        }
        ProductFiling f = new ProductFiling();
        f.setFgItemId(fg.getId());
        f.setFormulaVersionId(req.formulaVersionId());
        f.setFilingType(req.filingType());
        f.setStatus(FilingStatus.SUBMITTED);
        f.setSubmittedDate(req.submittedDate() == null ? time.today() : req.submittedDate());
        f.setNote(req.note() == null || req.note().isBlank() ? null : req.note().trim());
        f.setCreatedBy(currentUser.usernameOrSystem());
        f.setCreatedAt(time.now());
        filings.save(f);
        audit.log("提交备案", fg.getCode(), req.filingType().getLabel(), f.getNote());
        return toView(f);
    }

    @Transactional
    public FilingView complete(Long id, CompleteReq req) {
        ProductFiling f = require(id);
        if (f.getStatus() != FilingStatus.SUBMITTED) {
            throw new BizException("这条备案当前为" + f.getStatus().getLabel() + "，不能登记完成");
        }
        if (req.filedDate().isAfter(time.today())) {
            throw new BizException("完成日期不能晚于今天");
        }
        if (f.getFilingType() == FilingType.SPECIAL && req.expiryDate() == null) {
            throw new BizException("特殊化妆品注册证请填写有效期");
        }
        if (req.expiryDate() != null && !req.expiryDate().isAfter(req.filedDate())) {
            throw new BizException("有效期要晚于完成日期");
        }
        f.setFilingNo(req.filingNo().trim());
        f.setFiledDate(req.filedDate());
        f.setExpiryDate(req.expiryDate());
        f.setStatus(FilingStatus.FILED);
        filings.save(f);
        Item fg = lookup.item(f.getFgItemId());
        fg.setFilingNo(f.getFilingNo());
        items.save(fg);
        audit.log("备案完成", fg.getCode(), f.getFilingType().getLabel() + " " + f.getFilingNo()
                + (f.getExpiryDate() == null ? "" : "，有效期至 " + f.getExpiryDate()));
        return toView(f);
    }

    @Transactional
    public FilingView withdraw(Long id, WithdrawReq req) {
        ProductFiling f = require(id);
        if (f.getStatus() != FilingStatus.SUBMITTED) {
            throw new BizException("只有已提交、还没完成的备案可以撤回");
        }
        f.setStatus(FilingStatus.WITHDRAWN);
        f.setNote(req.reason().trim());
        filings.save(f);
        audit.log("撤回备案", lookup.item(f.getFgItemId()).getCode(), f.getFilingType().getLabel(), f.getNote());
        return toView(f);
    }

    private ProductFiling require(Long id) {
        return filings.lockById(id).orElseThrow(() -> BizException.notFound("备案记录"));
    }

    private FilingView toView(ProductFiling f) {
        Map<Long, Item> im = lookup.itemMap();
        Item fg = im.get(f.getFgItemId());
        Integer versionNo = f.getFormulaVersionId() == null ? null
                : versions.findById(f.getFormulaVersionId()).map(FormulaVersion::getVersionNo).orElse(null);
        Long daysLeft = f.getExpiryDate() == null ? null : ChronoUnit.DAYS.between(time.today(), f.getExpiryDate());
        return new FilingView(f.getId(), f.getFgItemId(), fg.getCode(), fg.getName(), f.getFormulaVersionId(), versionNo,
                f.getFilingType(), f.getFilingType().getLabel(), f.getFilingNo(), f.getStatus(), f.getStatus().getLabel(),
                f.getSubmittedDate(), f.getFiledDate(), f.getExpiryDate(), daysLeft, f.getNote(), f.getCreatedBy(), f.getCreatedAt());
    }
}
