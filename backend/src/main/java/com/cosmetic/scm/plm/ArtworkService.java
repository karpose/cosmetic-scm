package com.cosmetic.scm.plm;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.cosmetic.scm.attachment.AttachmentRepository;
import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.ArtworkStatus;
import com.cosmetic.scm.domain.FormulaStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.OwnerType;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.ItemRepository;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.CurrentUser;
import com.cosmetic.scm.security.SignatureService;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 标签设计稿评审：按包材分版本，关联成品和提交时的生效配方版本。QSO 逐项评审、上传了设计稿才能批准（不能是提交人），
 * 新版批准后旧版替代。配方变了，已批准的设计稿要重新评审。系统不判断设计稿内容，只管评审过程。规则见 docs/PLM-默认规则.md。
 */
@Service
@RequiredArgsConstructor
public class ArtworkService {

    /** 评审清单。批准时要全部勾选。 */
    public static final Map<String, String> CHECKLIST = new LinkedHashMap<>();

    static {
        CHECKLIST.put("INGREDIENTS", "全成分表与生效配方一致");
        CHECKLIST.put("FILING", "备案或注册编号正确");
        CHECKLIST.put("NET", "净含量正确");
        CHECKLIST.put("USAGE", "使用方法完整");
        CHECKLIST.put("WARNING", "警示用语完整");
        CHECKLIST.put("COMPANY", "注册人和生产企业信息正确");
        CHECKLIST.put("BATCH", "批号和限期使用日期的位置已留出");
    }

    private final LabelArtworkRepository artworks;
    private final FormulaVersionRepository versions;
    private final ItemRepository items;
    private final AttachmentRepository attachments;
    private final MasterService master;
    private final MasterLookup lookup;
    private final SignatureService signature;
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record CreateReq(@NotNull(message = "请选择包材") Long pkItemId, @NotNull(message = "请选择成品") Long fgItemId,
                            @NotBlank(message = "请写明设计稿内容或改了什么") @Size(max = 300) String changeNote) {
    }

    public record ApproveReq(List<String> checked, @NotBlank(message = "请输入签批密码") String password) {
    }

    public record NoteReq(@NotBlank(message = "请写明原因") @Size(max = 300) String note) {
    }

    public record CheckItem(String code, String label) {
    }

    /** stale：已批准的版本对应的配方已不是现行生效版本，要重新评审。 */
    public record ArtworkView(Long id, String artworkNo, Long pkItemId, String pkCode, String pkName, Long fgItemId, String fgCode, String fgName,
                              Long formulaVersionId, Integer formulaVersionNo, int versionNo, ArtworkStatus status, String statusLabel, boolean stale,
                              String changeNote, List<String> checked, String rejectNote, String createdBy, LocalDateTime createdAt, String approvedBy, LocalDateTime approvedAt) {
    }

    public record Overview(List<ArtworkView> artworks, List<CheckItem> checklist) {
    }

    @Transactional(readOnly = true)
    public Overview list() {
        return new Overview(views(artworks.findTop300ByOrderByIdDesc()), CHECKLIST.entrySet().stream().map(e -> new CheckItem(e.getKey(), e.getValue())).toList());
    }

    @Transactional(readOnly = true)
    public List<ArtworkView> pending() {
        return views(artworks.findByStatusInOrderById(List.of(ArtworkStatus.PENDING)));
    }

    /** 已批准、但配方已经变了的设计稿。 */
    @Transactional(readOnly = true)
    public List<ArtworkView> stale() {
        return views(artworks.findByStatusInOrderById(List.of(ArtworkStatus.APPROVED))).stream().filter(ArtworkView::stale).toList();
    }

    /** 包材现行（已批准）的设计稿。 */
    @Transactional(readOnly = true)
    public java.util.Optional<ArtworkView> current(Long pkItemId) {
        return artworks.findFirstByPkItemIdAndStatus(pkItemId, ArtworkStatus.APPROVED).map(a -> views(List.of(a)).get(0));
    }

    @Transactional
    public ArtworkView create(CreateReq req) {
        // 同一包材只能有一版待签批：先锁包材行，而且是事务第一句
        Item pk = items.lockById(req.pkItemId()).orElseThrow(() -> BizException.notFound("包材"));
        if (pk.getItemType() != ItemType.PK) {
            throw new BizException(pk.getName() + " 不是包材");
        }
        Item fg = lookup.item(req.fgItemId());
        if (fg.getItemType() != ItemType.FG) {
            throw new BizException(fg.getName() + " 不是成品");
        }
        FormulaVersion v = versions.findFirstByFgItemIdAndStatus(fg.getId(), FormulaStatus.EFFECTIVE)
                .orElseThrow(() -> new BizException(fg.getName() + " 还没有生效配方，成分表无从核对"));
        if (master.versionLines(v.getId()).stream().noneMatch(l -> l.getItemId().equals(pk.getId()))) {
            throw new BizException(pk.getName() + " 不在 " + fg.getName() + " 的生效配方里");
        }
        if (artworks.findFirstByPkItemIdAndStatus(pk.getId(), ArtworkStatus.PENDING).isPresent()) {
            throw new BizException(pk.getName() + " 已有一版设计稿待签批，签批或退回后再提交");
        }
        LabelArtwork a = new LabelArtwork();
        a.setArtworkNo(docNo.next("LA", time.today()));
        a.setPkItemId(pk.getId());
        a.setFgItemId(fg.getId());
        a.setFormulaVersionId(v.getId());
        a.setVersionNo(artworks.maxVersion(pk.getId()) + 1);
        a.setStatus(ArtworkStatus.PENDING);
        a.setChangeNote(req.changeNote().trim());
        a.setCreatedBy(currentUser.usernameOrSystem());
        a.setCreatedAt(time.now());
        artworks.save(a);
        audit.log("提交标签设计稿", a.getArtworkNo(), pk.getCode() + " 第 " + a.getVersionNo() + " 版，" + fg.getCode() + " V" + v.getVersionNo() + "：" + a.getChangeNote());
        return views(List.of(a)).get(0);
    }

    @Transactional
    public ArtworkView approve(Long id, ApproveReq req) {
        LabelArtwork a = lock(id);
        requirePending(a, "批准");
        AppUser signer = signature.verify(req.password());
        if (signer.getUsername().equals(a.getCreatedBy())) {
            throw new BizException("批准人不能是提交人");
        }
        Set<String> checked = req.checked() == null ? Set.of() : Set.copyOf(req.checked());
        List<String> missing = CHECKLIST.entrySet().stream().filter(e -> !checked.contains(e.getKey())).map(Map.Entry::getValue).toList();
        if (!missing.isEmpty()) {
            throw new BizException("评审项没有全部确认：" + String.join("、", missing));
        }
        if (!attachments.existsByOwnerTypeAndOwnerId(OwnerType.LABEL_ARTWORK, a.getId())) {
            throw new BizException("还没有上传设计稿文件，不能批准");
        }
        // 提交以后配方又变了：成分表是按旧配方核对的
        Long effective = versions.findFirstByFgItemIdAndStatus(a.getFgItemId(), FormulaStatus.EFFECTIVE).map(FormulaVersion::getId).orElse(null);
        if (!a.getFormulaVersionId().equals(effective)) {
            throw new BizException("提交后成品的生效配方变了，请退回后按新配方重新提交");
        }
        artworks.findFirstByPkItemIdAndStatus(a.getPkItemId(), ArtworkStatus.APPROVED).ifPresent(old -> {
            old.setStatus(ArtworkStatus.SUPERSEDED);
            artworks.save(old);
        });
        a.setStatus(ArtworkStatus.APPROVED);
        a.setChecklist(String.join(",", CHECKLIST.keySet()));
        a.setApprovedBy(signer.getUsername());
        a.setApprovedAt(time.now());
        artworks.save(a);
        audit.log("批准标签设计稿", a.getArtworkNo(), lookup.item(a.getPkItemId()).getCode() + " 第 " + a.getVersionNo() + " 版，评审项全部确认");
        return views(List.of(a)).get(0);
    }

    @Transactional
    public ArtworkView reject(Long id, NoteReq req) {
        LabelArtwork a = lock(id);
        requirePending(a, "退回");
        a.setStatus(ArtworkStatus.REJECTED);
        a.setRejectNote(req.note().trim());
        artworks.save(a);
        audit.log("退回标签设计稿", a.getArtworkNo(), lookup.item(a.getPkItemId()).getCode() + " 第 " + a.getVersionNo() + " 版", req.note().trim());
        return views(List.of(a)).get(0);
    }

    private LabelArtwork lock(Long id) {
        return artworks.lockById(id).orElseThrow(() -> BizException.notFound("标签设计稿"));
    }

    private static void requirePending(LabelArtwork a, String action) {
        if (a.getStatus() != ArtworkStatus.PENDING) {
            throw new BizException("设计稿 " + a.getArtworkNo() + " 当前为" + a.getStatus().getLabel() + "，不能" + action);
        }
    }

    private List<ArtworkView> views(List<LabelArtwork> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, Item> itemMap = lookup.itemMap();
        Map<Long, FormulaVersion> vmap = new HashMap<>();
        versions.findAllById(list.stream().map(LabelArtwork::getFormulaVersionId).distinct().toList()).forEach(v -> vmap.put(v.getId(), v));
        Map<Long, Long> effective = versions.findByStatus(FormulaStatus.EFFECTIVE).stream().collect(Collectors.toMap(FormulaVersion::getFgItemId, FormulaVersion::getId, (x, y) -> x));
        return list.stream().map(a -> {
            Item pk = itemMap.get(a.getPkItemId());
            Item fg = itemMap.get(a.getFgItemId());
            boolean stale = a.getStatus() == ArtworkStatus.APPROVED && !a.getFormulaVersionId().equals(effective.get(a.getFgItemId()));
            List<String> checked = a.getChecklist() == null || a.getChecklist().isBlank() ? List.of() : List.of(a.getChecklist().split(","));
            FormulaVersion v = vmap.get(a.getFormulaVersionId());
            return new ArtworkView(a.getId(), a.getArtworkNo(), a.getPkItemId(), pk.getCode(), pk.getName(), a.getFgItemId(), fg.getCode(), fg.getName(), a.getFormulaVersionId(),
                    v == null ? null : v.getVersionNo(), a.getVersionNo(), a.getStatus(), a.getStatus().getLabel(), stale, a.getChangeNote(), checked, a.getRejectNote(),
                    a.getCreatedBy(), a.getCreatedAt(), a.getApprovedBy(), a.getApprovedAt());
        }).toList();
    }
}
