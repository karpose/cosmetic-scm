package com.cosmetic.scm.plm;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.cosmetic.scm.attachment.AttachmentRepository;
import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.ArtworkStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.OwnerType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.ProofStatus;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.Stock;
import com.cosmetic.scm.inventory.StockRepository;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.plm.ArtworkService.ArtworkView;
import com.cosmetic.scm.plm.ArtworkService.CheckItem;
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
 * 包材打样（签样）和印刷批次记录。每家印刷供应商对每一版已批准的设计稿打样，质量对照设计稿逐项核对、上传样张后签样（不能是登记人）；
 * 有签样，这家供应商的这一版包材才能放行。放行时把设计稿版本和签样记到批次上，换版后能查到按旧版印刷、还在库的批次。
 * 规则见 docs/PLM-默认规则.md。
 */
@Service
@RequiredArgsConstructor
public class ArtworkProofService {

    /** 签样核对清单。签样时要全部勾选。 */
    public static final Map<String, String> CHECKLIST = new LinkedHashMap<>();

    static {
        CHECKLIST.put("TEXT", "文字和全成分表与设计稿一致");
        CHECKLIST.put("COLOR", "颜色与设计稿或色卡一致");
        CHECKLIST.put("CODE", "条码、二维码能扫出且内容正确");
        CHECKLIST.put("SIZE", "尺寸、刀模和出血位正确");
        CHECKLIST.put("MATERIAL", "材质和印刷工艺符合要求");
        CHECKLIST.put("PRINT_AREA", "批号和限期使用日期的喷印位置清楚");
    }

    private final ArtworkProofRepository proofs;
    private final LabelArtworkRepository artworks;
    private final ArtworkService artworkService;
    private final BatchRepository batches;
    private final StockRepository stocks;
    private final AttachmentRepository attachments;
    private final MasterLookup lookup;
    private final SignatureService signature;
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record CreateReq(@NotNull(message = "请选择印刷供应商") Long supplierId, @NotNull(message = "请选择收到样张的日期") LocalDate proofDate,
                            @Size(max = 300) String note) {
    }

    public record ApproveReq(List<String> checked, @NotBlank(message = "请输入签批密码") String password) {
    }

    public record NoteReq(@NotBlank(message = "请写明原因") @Size(max = 300) String note) {
    }

    /** current：设计稿是现行版本（已批准、配方没变）且这次打样已签样，这家供应商的包材凭它放行。 */
    public record ProofView(Long id, String proofNo, Long artworkId, String artworkNo, int artworkVersionNo, Long pkItemId, String pkCode, String pkName,
                            Long supplierId, String supplierCode, String supplierName, int roundNo, LocalDate proofDate, ProofStatus status, String statusLabel,
                            boolean current, String note, List<String> checked, String resultNote, String createdBy, LocalDateTime createdAt,
                            String signedBy, LocalDateTime signedAt) {
    }

    /** 按这一版设计稿放行的包材批次。onHand：现在的库存。 */
    public record PrintedBatch(Long batchId, String batchNo, String supplierName, String extBatchNo, String statusLabel, BigDecimal qtyIn, BigDecimal onHand,
                               String proofNo, LocalDateTime createdAt) {
    }

    public record ArtworkDetail(ArtworkView artwork, List<ProofView> proofs, List<PrintedBatch> batches, List<CheckItem> proofChecklist) {
    }

    /** 包材批次放行前的核对信息。proofRequired：批次来自供应商收货，要有这家供应商的签样。 */
    public record ReleaseCheck(ArtworkView artwork, ProofView proof, String supplierName, boolean proofRequired) {
    }

    /** 放行核对通过后的结果：写进检验记录的文字，以及记到批次上的设计稿和签样。 */
    public record ReleaseResult(String text, Long artworkId, Long proofId) {
    }

    @Transactional(readOnly = true)
    public ArtworkDetail detail(Long artworkId) {
        LabelArtwork a = artworks.findById(artworkId).orElseThrow(() -> BizException.notFound("标签设计稿"));
        ArtworkView av = artworkService.view(a);
        List<ProofView> pv = views(proofs.findByArtworkIdOrderById(a.getId()), Map.of(a.getId(), av));
        return new ArtworkDetail(av, pv, printed(a.getId()), checklist());
    }

    /** 待签样的打样，总览提醒用。 */
    @Transactional(readOnly = true)
    public List<ProofView> pending() {
        return views(proofs.findByStatusInOrderById(List.of(ProofStatus.PENDING)), null);
    }

    @Transactional
    public ProofView create(Long artworkId, CreateReq req) {
        // 同一版设计稿、同一家供应商只能有一次待签样：先锁设计稿行，而且是事务第一句
        LabelArtwork a = lockArtwork(artworkId);
        ArtworkView av = requireCurrent(a, "打样");
        Partner sup = lookup.partner(req.supplierId());
        if (sup.getKind() != PartnerKind.SUPPLIER) {
            throw new BizException(sup.getName() + " 不是供应商");
        }
        if (req.proofDate().isAfter(time.today())) {
            throw new BizException("收到样张的日期不能晚于今天");
        }
        if (proofs.findFirstByArtworkIdAndSupplierIdAndStatus(a.getId(), sup.getId(), ProofStatus.PENDING).isPresent()) {
            throw new BizException(sup.getName() + " 这一版已有一次打样待签样，签样或判不合格后再登记");
        }
        ArtworkProof p = new ArtworkProof();
        p.setProofNo(docNo.next("PF", time.today()));
        p.setArtworkId(a.getId());
        p.setSupplierId(sup.getId());
        p.setRoundNo(proofs.maxRound(a.getId(), sup.getId()) + 1);
        p.setProofDate(req.proofDate());
        p.setStatus(ProofStatus.PENDING);
        p.setNote(req.note() == null || req.note().isBlank() ? null : req.note().trim());
        p.setCreatedBy(currentUser.usernameOrSystem());
        p.setCreatedAt(time.now());
        proofs.save(p);
        audit.log("登记包材打样", p.getProofNo(), av.pkCode() + " 设计稿 " + a.getArtworkNo() + " 第 " + a.getVersionNo() + " 版，" + sup.getName() + " 第 " + p.getRoundNo() + " 次打样");
        return views(List.of(p), Map.of(a.getId(), av)).get(0);
    }

    @Transactional
    public ProofView approve(Long proofId, ApproveReq req) {
        ArtworkProof p = lockForWrite(proofId);
        requireStatus(p, ProofStatus.PENDING, "签样");
        LabelArtwork a = artworks.findById(p.getArtworkId()).orElseThrow();
        ArtworkView av = requireCurrent(a, "签样");
        AppUser signer = signature.verify(req.password());
        if (signer.getUsername().equals(p.getCreatedBy())) {
            throw new BizException("签样人不能是登记人");
        }
        Set<String> checked = req.checked() == null ? Set.of() : Set.copyOf(req.checked());
        List<String> missing = CHECKLIST.entrySet().stream().filter(e -> !checked.contains(e.getKey())).map(Map.Entry::getValue).toList();
        if (!missing.isEmpty()) {
            throw new BizException("核对项没有全部确认：" + String.join("、", missing));
        }
        if (!attachments.existsByOwnerTypeAndOwnerId(OwnerType.ARTWORK_PROOF, p.getId())) {
            throw new BizException("还没有上传样张照片或扫描件，不能签样");
        }
        proofs.findFirstByArtworkIdAndSupplierIdAndStatus(p.getArtworkId(), p.getSupplierId(), ProofStatus.APPROVED).ifPresent(old -> {
            old.setStatus(ProofStatus.SUPERSEDED);
            proofs.save(old);
        });
        p.setStatus(ProofStatus.APPROVED);
        p.setChecklist(String.join(",", CHECKLIST.keySet()));
        p.setSignedBy(signer.getUsername());
        p.setSignedAt(time.now());
        proofs.save(p);
        audit.log("包材签样", p.getProofNo(), av.pkCode() + " 设计稿 " + a.getArtworkNo() + " 第 " + a.getVersionNo() + " 版，"
                + lookup.partner(p.getSupplierId()).getName() + " 第 " + p.getRoundNo() + " 次打样，核对项全部确认");
        return views(List.of(p), Map.of(a.getId(), av)).get(0);
    }

    @Transactional
    public ProofView reject(Long proofId, NoteReq req) {
        ArtworkProof p = lockForWrite(proofId);
        requireStatus(p, ProofStatus.PENDING, "判不合格");
        return close(p, ProofStatus.REJECTED, "包材打样不合格", req.note());
    }

    /** 已签样的作废：作废后这家供应商的这一版包材要重新打样签样才能放行，已放行的批次不受影响。 */
    @Transactional
    public ProofView voidProof(Long proofId, NoteReq req) {
        ArtworkProof p = lockForWrite(proofId);
        requireStatus(p, ProofStatus.APPROVED, "作废");
        return close(p, ProofStatus.VOID, "作废包材签样", req.note());
    }

    /** 放行包材批次前的核对信息，没有现行设计稿时为空。只读，给页面用。 */
    @Transactional(readOnly = true)
    public Optional<ReleaseCheck> releaseCheck(Long batchId) {
        Batch b = batches.findById(batchId).orElseThrow(() -> BizException.notFound("批次"));
        Item item = lookup.item(b.getItemId());
        if (item.getItemType() != ItemType.PK) {
            return Optional.empty();
        }
        return artworkService.current(item.getId()).map(av -> {
            Long supplierId = supplierOf(b);
            ProofView pv = supplierId == null ? null : proofs.findFirstByArtworkIdAndSupplierIdAndStatus(av.id(), supplierId, ProofStatus.APPROVED)
                    .map(p -> views(List.of(p), Map.of(av.id(), av)).get(0)).orElse(null);
            return new ReleaseCheck(av, pv, supplierId == null ? null : lookup.partner(supplierId).getName(), supplierId != null);
        });
    }

    /**
     * 放行包材批次时核对设计稿和签样，调用方已锁批次。没有现行设计稿返回 null；配方变了、没确认印刷内容、
     * 供应商收货的批次没有这家供应商的签样，都不能放行。
     */
    @Transactional
    public ReleaseResult checkForRelease(Batch b, Item item, boolean confirmed) {
        if (item.getItemType() != ItemType.PK) {
            return null;
        }
        // 只查 id 再加锁读取（先读实体的话，加锁拿到的是缓存里的旧状态）。锁住设计稿：和签样、作废、换版串行，避免按刚作废的签样放行
        Long artworkId = artworks.findIdsByPkItemIdAndStatus(item.getId(), ArtworkStatus.APPROVED).stream().findFirst().orElse(null);
        if (artworkId == null) {
            return null;
        }
        LabelArtwork a = lockArtwork(artworkId);
        if (a.getStatus() != ArtworkStatus.APPROVED) {
            throw new BizException("设计稿刚刚换版，请刷新后重新核对");
        }
        ArtworkView av = artworkService.view(a);
        String ref = a.getArtworkNo() + " 第 " + a.getVersionNo() + " 版";
        if (av.stale()) {
            throw new BizException("设计稿 " + ref + " 对应的成品配方已变，要先重新评审设计稿，这批包材不能放行（可以拒收）");
        }
        if (!confirmed) {
            throw new BizException("请确认印刷内容与已批准的设计稿 " + ref + " 一致");
        }
        Long supplierId = supplierOf(b);
        if (supplierId == null) {
            return new ReleaseResult("印刷内容与设计稿 " + ref + " 一致", a.getId(), null);
        }
        Partner sup = lookup.partner(supplierId);
        ArtworkProof p = proofs.findFirstByArtworkIdAndSupplierIdAndStatus(a.getId(), supplierId, ProofStatus.APPROVED)
                .orElseThrow(() -> new BizException(sup.getName() + " 还没有设计稿 " + ref + " 的签样，先打样签样再放行（可以拒收）"));
        return new ReleaseResult("印刷内容与设计稿 " + ref + " 一致，签样 " + p.getProofNo(), a.getId(), p.getId());
    }

    private ProofView close(ArtworkProof p, ProofStatus status, String action, String note) {
        p.setStatus(status);
        p.setResultNote(note.trim());
        proofs.save(p);
        LabelArtwork a = artworks.findById(p.getArtworkId()).orElseThrow();
        audit.log(action, p.getProofNo(), lookup.item(a.getPkItemId()).getCode() + " 设计稿 " + a.getArtworkNo() + " 第 " + a.getVersionNo() + " 版，"
                + lookup.partner(p.getSupplierId()).getName(), note.trim());
        return views(List.of(p), null).get(0);
    }

    /**
     * 打样单写操作：先锁所属的设计稿（和换版、放行串行），再锁打样单。设计稿 id 登记后不变，先查出来不会错；
     * 但只能查 id，不能先把打样单读进来，否则加锁读取拿到的是缓存里的旧状态，并发签样会都成功。
     */
    private ArtworkProof lockForWrite(Long proofId) {
        Long artworkId = proofs.findArtworkIdById(proofId).orElseThrow(() -> BizException.notFound("包材打样"));
        lockArtwork(artworkId);
        return proofs.lockById(proofId).orElseThrow(() -> BizException.notFound("包材打样"));
    }

    private LabelArtwork lockArtwork(Long id) {
        return artworks.lockById(id).orElseThrow(() -> BizException.notFound("标签设计稿"));
    }

    private ArtworkView requireCurrent(LabelArtwork a, String action) {
        if (a.getStatus() != ArtworkStatus.APPROVED) {
            throw new BizException("设计稿 " + a.getArtworkNo() + " 当前为" + a.getStatus().getLabel() + "，只有已批准的现行版本才能" + action);
        }
        ArtworkView av = artworkService.view(a);
        if (av.stale()) {
            throw new BizException("设计稿 " + a.getArtworkNo() + " 对应的成品配方已变，要先重新评审设计稿才能" + action);
        }
        return av;
    }

    private static void requireStatus(ArtworkProof p, ProofStatus expected, String action) {
        if (p.getStatus() != expected) {
            throw new BizException("打样 " + p.getProofNo() + " 当前为" + p.getStatus().getLabel() + "，不能" + action);
        }
    }

    /** 采购收货的批次才有印刷供应商；期初导入的没有，只核对印刷内容。 */
    private static Long supplierOf(Batch b) {
        return b.getSourceType() == com.cosmetic.scm.domain.SourceType.PO ? b.getPartnerId() : null;
    }

    private List<PrintedBatch> printed(Long artworkId) {
        List<Batch> list = batches.findByArtworkIdInOrderById(List.of(artworkId));
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, BigDecimal> onHand = new HashMap<>();
        stocks.findByBatchIdIn(list.stream().map(Batch::getId).toList()).forEach(s -> onHand.merge(s.getBatchId(), s.getQty(), BigDecimal::add));
        Map<Long, String> proofNos = new HashMap<>();
        proofs.findAllById(list.stream().map(Batch::getProofId).filter(java.util.Objects::nonNull).distinct().toList()).forEach(p -> proofNos.put(p.getId(), p.getProofNo()));
        Map<Long, Partner> partners = lookup.partnerMap();
        return list.stream().map(b -> new PrintedBatch(b.getId(), b.getBatchNo(), b.getPartnerId() == null ? null : partners.get(b.getPartnerId()).getName(),
                b.getExtBatchNo(), b.getStatus().getLabel(), b.getQtyIn(), onHand.getOrDefault(b.getId(), BigDecimal.ZERO), proofNos.get(b.getProofId()), b.getCreatedAt())).toList();
    }

    private static List<CheckItem> checklist() {
        return CHECKLIST.entrySet().stream().map(e -> new CheckItem(e.getKey(), e.getValue())).toList();
    }

    /** known：已经算好的设计稿视图，没给的按需查。 */
    private List<ProofView> views(List<ArtworkProof> list, Map<Long, ArtworkView> known) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, ArtworkView> avs = new HashMap<>(known == null ? Map.of() : known);
        List<Long> missing = list.stream().map(ArtworkProof::getArtworkId).filter(id -> !avs.containsKey(id)).distinct().toList();
        if (!missing.isEmpty()) {
            artworkService.viewAll(artworks.findAllById(missing)).forEach(av -> avs.put(av.id(), av));
        }
        Map<Long, Partner> partners = lookup.partnerMap();
        return list.stream().map(p -> {
            ArtworkView av = avs.get(p.getArtworkId());
            Partner sup = partners.get(p.getSupplierId());
            boolean current = p.getStatus() == ProofStatus.APPROVED && av.status() == ArtworkStatus.APPROVED && !av.stale();
            List<String> checked = p.getChecklist() == null || p.getChecklist().isBlank() ? List.of() : List.of(p.getChecklist().split(","));
            return new ProofView(p.getId(), p.getProofNo(), av.id(), av.artworkNo(), av.versionNo(), av.pkItemId(), av.pkCode(), av.pkName(), sup.getId(), sup.getCode(),
                    sup.getName(), p.getRoundNo(), p.getProofDate(), p.getStatus(), p.getStatus().getLabel(), current, p.getNote(), checked, p.getResultNote(),
                    p.getCreatedBy(), p.getCreatedAt(), p.getSignedBy(), p.getSignedAt());
        }).toList();
    }
}
