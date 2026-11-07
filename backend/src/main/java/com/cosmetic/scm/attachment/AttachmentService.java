package com.cosmetic.scm.attachment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.complaint.ComplaintRepository;
import com.cosmetic.scm.domain.MoStatus;
import com.cosmetic.scm.domain.MoType;
import com.cosmetic.scm.domain.OwnerType;
import com.cosmetic.scm.purchase.PurchaseOrder;
import com.cosmetic.scm.domain.PoStatus;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.master.PartnerRepository;
import com.cosmetic.scm.mes.EquipmentRepository;
import com.cosmetic.scm.production.MfgOrder;
import com.cosmetic.scm.production.MfgOrderRepository;
import com.cosmetic.scm.purchase.PurchaseOrderRepository;
import com.cosmetic.scm.recall.RecallRepository;
import com.cosmetic.scm.salesreturn.SalesReturnRepository;
import com.cosmetic.scm.security.CurrentUser;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 附件：COA、检验报告、受托厂出厂报告、资质证书、投诉照片等。
 * 只增不删；文件类型按扩展名白名单，并核对文件头和扩展名一致；
 * 上传时记录 SHA-256，下载时重新计算，不一致就拒绝下载（防止文件被替换而记录不知情）。
 */
@Service
@RequiredArgsConstructor
public class AttachmentService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy/MM");

    /** 允许的扩展名 → 下载时用的 Content-Type */
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("pdf", "application/pdf"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("png", "image/png"), Map.entry("webp", "image/webp"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("csv", "text/csv"), Map.entry("txt", "text/plain"));

    private final AttachmentRepository attachments;
    private final FileStorage storage;
    private final BatchRepository batches;
    private final PurchaseOrderRepository purchaseOrders;
    private final MfgOrderRepository mfgOrders;
    private final ComplaintRepository complaints;
    private final RecallRepository recalls;
    private final SalesReturnRepository returns;
    private final PartnerRepository partners;
    private final EquipmentRepository equipment;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;
    private final com.cosmetic.scm.security.UserRepository users;
    private final com.cosmetic.scm.settlement.SupplierStatementRepository statements;
    private final com.cosmetic.scm.plm.ComplianceDocRepository complianceDocs;
    private final com.cosmetic.scm.plm.StabilityStudyRepository stabilityStudies;
    private final com.cosmetic.scm.quality.QualitySpecRepository qualitySpecs;
    private final com.cosmetic.scm.plm.NpdProjectRepository npdProjects;

    @Value("${scm.storage.max-file-mb:20}")
    private int maxFileMb;

    public record AttachmentView(Long id, OwnerType ownerType, Long ownerId, String fileName, String contentType,
                                 long sizeBytes, String sha256, String note, String uploadedBy, LocalDateTime uploadedAt) {
    }

    public record Download(String fileName, String contentType, byte[] bytes) {
    }

    @Transactional
    public AttachmentView upload(OwnerType ownerType, Long ownerId, MultipartFile file, String note) {
        requireAccess(ownerType);
        if (!ownerRepo(ownerType).existsById(ownerId)) {
            throw BizException.notFound(ownerType.getLabel());
        }
        return store(ownerType, ownerId, file, note);
    }

    @Transactional(readOnly = true)
    public List<AttachmentView> list(OwnerType ownerType, Long ownerId) {
        return attachments.findByOwnerTypeAndOwnerIdOrderByIdDesc(ownerType, ownerId).stream().map(AttachmentService::view).toList();
    }

    @Transactional(readOnly = true)
    public Download download(Long id) {
        Attachment a = attachments.findById(id).orElseThrow(() -> BizException.notFound("附件"));
        requireAccess(a.getOwnerType());
        return read(a);
    }

    /** 内部接口查附件列表：对账单的发票只给采购和财务。 */
    @Transactional(readOnly = true)
    public List<AttachmentView> listInternal(OwnerType ownerType, Long ownerId) {
        requireAccess(ownerType);
        return list(ownerType, ownerId);
    }

    private static void requireAccess(OwnerType ownerType) {
        if (ownerType == OwnerType.SUPPLIER_STATEMENT && !com.cosmetic.scm.security.PriceAccess.allowed()) {
            throw new BizException(org.springframework.http.HttpStatus.FORBIDDEN, "没有权限查看对账单的附件");
        }
    }

    // ---------- 代工厂门户：只能碰委托给本厂的单子 ----------

    @Transactional
    public AttachmentView uploadForOem(Long moId, MultipartFile file, String note) {
        MoStatus status = requireOwnMo(moId).getStatus();
        if (status == MoStatus.COMPLETED || status == MoStatus.CANCELLED) {
            throw new BizException("这张委托单" + (status == MoStatus.COMPLETED ? "已完工入库" : "已取消") + "，不能再上传附件；如需补充资料请联系品牌方");
        }
        return store(OwnerType.MFG_ORDER, moId, file, note);
    }

    @Transactional(readOnly = true)
    public List<AttachmentView> listForOem(Long moId) {
        requireOwnMo(moId);
        return list(OwnerType.MFG_ORDER, moId);
    }

    @Transactional(readOnly = true)
    public Download downloadForOem(Long id) {
        Attachment a = attachments.findById(id).filter(x -> x.getOwnerType() == OwnerType.MFG_ORDER)
                .orElseThrow(() -> BizException.notFound("附件"));
        requireOwnMo(a.getOwnerId());
        return read(a);
    }

    // ---------- 供应商门户：只碰本单位的采购单和档案，只看本单位账号传的文件 ----------
    // 内部传到采购单、单位上的文件（审核报告等）不给供应商看

    @Transactional
    public AttachmentView uploadForSupplierOrder(Long poId, MultipartFile file, String note) {
        if (requireOwnPo(poId).getStatus() == PoStatus.CANCELLED) {
            throw new BizException("这张采购单已取消，不能上传附件");
        }
        return store(OwnerType.PURCHASE_ORDER, poId, file, note);
    }

    @Transactional(readOnly = true)
    public List<AttachmentView> listForSupplierOrder(Long poId) {
        requireOwnPo(poId);
        return uploadedByMyPartner(list(OwnerType.PURCHASE_ORDER, poId));
    }

    @Transactional
    public AttachmentView uploadForSupplierProfile(MultipartFile file, String note) {
        return store(OwnerType.PARTNER, currentUser.portalPartnerId(), file, note);
    }

    @Transactional(readOnly = true)
    public List<AttachmentView> listForSupplierProfile() {
        return uploadedByMyPartner(list(OwnerType.PARTNER, currentUser.portalPartnerId()));
    }

    @Transactional(readOnly = true)
    public Download downloadForSupplier(Long id) {
        Attachment a = attachments.findById(id).orElseThrow(() -> BizException.notFound("附件"));
        Long mine = currentUser.portalPartnerId();
        boolean ownerIsMine = switch (a.getOwnerType()) {
            case PURCHASE_ORDER -> purchaseOrders.findById(a.getOwnerId()).filter(p -> mine.equals(p.getSupplierId())).isPresent();
            case PARTNER -> mine.equals(a.getOwnerId());
            case SUPPLIER_STATEMENT -> statements.findById(a.getOwnerId()).filter(x -> mine.equals(x.getSupplierId())).isPresent();
            default -> false;
        };
        if (!ownerIsMine || !users.usernamesOfPartner(mine).contains(a.getUploadedBy())) {
            throw BizException.notFound("附件");
        }
        return read(a);
    }

    @Transactional
    public AttachmentView uploadForSupplierStatement(Long id, MultipartFile file, String note) {
        requireOwnStatement(id);
        return store(OwnerType.SUPPLIER_STATEMENT, id, file, note);
    }

    @Transactional(readOnly = true)
    public List<AttachmentView> listForSupplierStatement(Long id) {
        requireOwnStatement(id);
        return uploadedByMyPartner(list(OwnerType.SUPPLIER_STATEMENT, id));
    }

    private void requireOwnStatement(Long id) {
        Long mine = currentUser.portalPartnerId();
        statements.findById(id).filter(x -> mine.equals(x.getSupplierId())).orElseThrow(() -> BizException.notFound("对账单"));
    }

    private List<AttachmentView> uploadedByMyPartner(List<AttachmentView> all) {
        List<String> mine = users.usernamesOfPartner(currentUser.portalPartnerId());
        return all.stream().filter(a -> mine.contains(a.uploadedBy())).toList();
    }

    private PurchaseOrder requireOwnPo(Long poId) {
        Long mine = currentUser.portalPartnerId();
        return purchaseOrders.findById(poId).filter(p -> mine.equals(p.getSupplierId()))
                .orElseThrow(() -> BizException.notFound("采购单"));
    }

    // ---------- 内部 ----------

    private AttachmentView store(OwnerType ownerType, Long ownerId, MultipartFile file, String note) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的文件");
        }
        if (file.getSize() > maxFileMb * 1024L * 1024L) {
            throw new BizException("文件超过 " + maxFileMb + " MB，请压缩后再传");
        }
        String name = cleanName(file.getOriginalFilename());
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        String contentType = TYPES.get(ext);
        if (contentType == null) {
            throw new BizException("不支持的文件类型。可上传：PDF、图片（jpg / png / webp）、Excel（xlsx）、Word（docx）、CSV、TXT");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException("读取上传文件失败，请重试");
        }
        if (!headerMatches(ext, bytes)) {
            throw new BizException("文件内容和扩展名 ." + ext + " 不符，请确认文件没有被改过扩展名");
        }
        LocalDateTime now = time.now();
        String key = now.format(MONTH) + "/" + UUID.randomUUID() + "." + ext;
        storage.write(key, bytes);

        Attachment a = new Attachment();
        a.setOwnerType(ownerType);
        a.setOwnerId(ownerId);
        a.setFileName(name);
        a.setContentType(contentType);
        a.setSizeBytes(bytes.length);
        a.setSha256(sha256(bytes));
        a.setStorageKey(key);
        a.setNote(note == null || note.isBlank() ? null : note.trim().substring(0, Math.min(note.trim().length(), 200)));
        a.setUploadedBy(currentUser.usernameOrSystem());
        a.setUploadedAt(now);
        attachments.save(a);
        String auditDetail = name + "（" + (bytes.length / 1024) + " KB，SHA-256 "
                + a.getSha256().substring(0, 12) + "…）";
        // 对账单的发票文件名可能带金额，审计里按角色遮盖
        if (ownerType == OwnerType.SUPPLIER_STATEMENT) {
            audit.logMoney("上传附件", ownerType.getLabel() + " #" + ownerId, auditDetail, a.getNote());
        } else {
            audit.log("上传附件", ownerType.getLabel() + " #" + ownerId, auditDetail, a.getNote());
        }
        return view(a);
    }

    private Download read(Attachment a) {
        byte[] bytes;
        try {
            bytes = storage.read(a.getStorageKey());
        } catch (UncheckedIOException e) {
            throw new BizException("附件 " + a.getFileName() + " 的文件读取失败，可能已丢失，请联系管理员从备份恢复");
        }
        if (!sha256(bytes).equals(a.getSha256())) {
            throw new BizException("附件 " + a.getFileName() + " 校验不一致，文件可能已被改动，已拒绝下载，请联系管理员");
        }
        return new Download(a.getFileName(), a.getContentType(), bytes);
    }

    private MfgOrder requireOwnMo(Long moId) {
        Long factoryId = currentUser.portalPartnerId();
        return mfgOrders.findById(moId).filter(m -> m.getMoType() == MoType.OEM && factoryId.equals(m.getFactoryId()))
                .orElseThrow(() -> BizException.notFound("委托单"));
    }

    private JpaRepository<?, Long> ownerRepo(OwnerType t) {
        return switch (t) {
            case BATCH -> batches;
            case PURCHASE_ORDER -> purchaseOrders;
            case MFG_ORDER -> mfgOrders;
            case COMPLAINT -> complaints;
            case RECALL -> recalls;
            case SALES_RETURN -> returns;
            case PARTNER -> partners;
            case EQUIPMENT -> equipment;
            case SUPPLIER_STATEMENT -> statements;
            case COMPLIANCE_DOC -> complianceDocs;
            case STABILITY_STUDY -> stabilityStudies;
            case QUALITY_SPEC -> qualitySpecs;
            case NPD_PROJECT -> npdProjects;
        };
    }

    /** 文件头与扩展名一致：二进制格式看魔数，文本格式要求不含空字节。 */
    static boolean headerMatches(String ext, byte[] b) {
        return switch (ext) {
            case "pdf" -> startsWith(b, '%', 'P', 'D', 'F');
            case "png" -> startsWith(b, 0x89, 'P', 'N', 'G');
            case "jpg", "jpeg" -> startsWith(b, 0xFF, 0xD8, 0xFF);
            case "webp" -> startsWith(b, 'R', 'I', 'F', 'F') && b.length > 11 && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
            case "xlsx", "docx" -> startsWith(b, 'P', 'K', 3, 4);
            case "csv", "txt" -> {
                for (int i = 0; i < Math.min(b.length, 8192); i++) {
                    if (b[i] == 0) {
                        yield false;
                    }
                }
                yield true;
            }
            default -> false;
        };
    }

    private static boolean startsWith(byte[] b, int... magic) {
        if (b.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if ((b[i] & 0xFF) != magic[i]) {
                return false;
            }
        }
        return true;
    }

    private static String cleanName(String original) {
        String n = original == null ? "file" : original.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"]", "").trim();
        if (n.isEmpty()) {
            n = "file";
        }
        return n.length() <= 200 ? n : n.substring(n.length() - 200);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AttachmentView view(Attachment a) {
        return new AttachmentView(a.getId(), a.getOwnerType(), a.getOwnerId(), a.getFileName(), a.getContentType(),
                a.getSizeBytes(), a.getSha256(), a.getNote(), a.getUploadedBy(), a.getUploadedAt());
    }
}
