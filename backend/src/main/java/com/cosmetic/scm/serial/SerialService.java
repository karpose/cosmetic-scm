package com.cosmetic.scm.serial;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.CaseStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.rules.SerialCodes;
import com.cosmetic.scm.domain.rules.ShelfLife;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.InventoryService;
import com.cosmetic.scm.inventory.InventoryService.BatchView;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.recall.RecallRepository;
import com.cosmetic.scm.security.CurrentUser;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 一物一码：成品批次按件生成随机防伪溯源码，导出给印刷厂；消费者扫码查询正品、批次和效期，
 * 召回或冻结中的批次会提示停止使用，同一码被多次查询会提示谨防仿冒。
 */
@Service
@RequiredArgsConstructor
public class SerialService {

    /** 单次最多生成多少个码，避免误操作一次生成过多。 */
    public static final int MAX_PER_REQUEST = 20000;

    private final SerialCodeRepository codes;
    private final BatchRepository batches;
    private final RecallRepository recalls;
    private final InventoryService inventory;
    private final MasterLookup lookup;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    @Value("${scm.serial.verify-url-prefix:http://localhost:5173/v/}")
    private String verifyUrlPrefix;

    public record GenerateReq(@NotBlank(message = "请填写成品批号") String batchNo,
                              @Min(value = 1, message = "数量至少 1") @Max(value = MAX_PER_REQUEST, message = "单次最多生成 20000 个") int qty) {
    }

    public record BatchCodes(Long batchId, String batchNo, String itemName, String statusLabel, BigDecimal qtyIn,
                             long generated, long scannedCodes, long scans) {
    }

    public record CodeView(String code, String displayCode, int seq, String batchNo, String itemName, int scanCount,
                           LocalDateTime firstScanAt, LocalDateTime lastScanAt, LocalDateTime createdAt) {
    }

    /** status：GENUINE 正品 / REPEATED 正品但多次查询 / RECALLED 召回或暂停销售 / EXPIRED 已过期 / UNRELEASED 未放行 / NOT_FOUND 查不到 */
    public record VerifyView(String status, String title, String message, String displayCode, String productName,
                             String filingNo, BigDecimal netG, String batchNo, LocalDate mfgDate, LocalDate expDate,
                             int scanCount, LocalDateTime firstScanAt) {
    }

    // ---------- 系统内 ----------

    @Transactional(readOnly = true)
    public List<BatchCodes> batches() {
        List<BatchView> fg = inventory.listBatches(null, null).stream().filter(b -> "FG".equals(b.itemType())).toList();
        Map<Long, long[]> stats = new HashMap<>();
        if (!fg.isEmpty()) {
            for (Object[] r : codes.statsByBatch(fg.stream().map(BatchView::id).toList())) {
                stats.put((Long) r[0], new long[] {num(r[1]), num(r[2]), num(r[3])});
            }
        }
        return fg.stream().map(b -> {
            long[] s = stats.getOrDefault(b.id(), new long[3]);
            return new BatchCodes(b.id(), b.batchNo(), b.itemName(), b.statusLabel(), b.qtyIn(), s[0], s[1], s[2]);
        }).toList();
    }

    @Transactional
    public BatchCodes generate(GenerateReq req) {
        // 锁批次：同时生码时不能超过入库数
        Batch b = batches.lockByBatchNo(req.batchNo() == null ? null : req.batchNo().trim()).orElseThrow(() -> BizException.notFound("批次 " + req.batchNo() + " "));
        Item item = lookup.item(b.getItemId());
        if (item.getItemType() != ItemType.FG) {
            throw new BizException("只有成品批次需要生成一物一码");
        }
        if (b.getStatus() != BatchStatus.RELEASED && b.getStatus() != BatchStatus.PENDING_RELEASE) {
            throw new BizException("批次 " + b.getBatchNo() + " 当前为" + b.getStatus().getLabel() + "，不能生成防伪码");
        }
        long have = codes.countByBatchId(b.getId());
        if (BigDecimal.valueOf(have + req.qty()).compareTo(b.getQtyIn()) > 0) {
            throw new BizException("批次 " + b.getBatchNo() + " 入库 " + b.getQtyIn().stripTrailingZeros().toPlainString()
                    + " 件，已生成 " + have + " 个码，最多还能生成 " + b.getQtyIn().subtract(BigDecimal.valueOf(have)).max(BigDecimal.ZERO)
                    .stripTrailingZeros().toPlainString() + " 个");
        }
        Integer max = codes.maxSeq(b.getId());
        int seq = max == null ? 0 : max;
        LocalDateTime now = time.now();
        String by = currentUser.usernameOrSystem();
        List<SerialCode> batch = new ArrayList<>();
        for (int i = 0; i < req.qty(); i++) {
            String c;
            do {
                c = SerialCodes.random();
            } while (codes.existsByCode(c));
            SerialCode s = new SerialCode();
            s.setCode(c);
            s.setBatchId(b.getId());
            s.setSeq(++seq);
            s.setCreatedBy(by);
            s.setCreatedAt(now);
            batch.add(s);
        }
        codes.saveAll(batch);
        audit.log("生成防伪码", b.getBatchNo(), item.getName() + "，新增 " + req.qty() + " 个，累计 " + (have + req.qty()) + " 个");
        return batches().stream().filter(x -> x.batchId().equals(b.getId())).findFirst().orElseThrow();
    }

    /** 导出给印刷厂的 CSV：带 BOM，Excel 打开不乱码。 */
    @Transactional(readOnly = true)
    public String exportCsv(Long batchId) {
        Batch b = inventory.batch(batchId);
        Item item = lookup.item(b.getItemId());
        StringBuilder sb = new StringBuilder("﻿序号,防伪码,扫码链接,批号,产品\r\n");
        for (SerialCode s : codes.findByBatchIdOrderBySeq(batchId)) {
            sb.append(s.getSeq()).append(',').append(SerialCodes.display(s.getCode())).append(',')
                    .append(verifyUrlPrefix).append(s.getCode()).append(',').append(b.getBatchNo()).append(',')
                    .append(item.getName().replace(",", "，")).append("\r\n");
        }
        audit.log("导出防伪码", b.getBatchNo(), codes.countByBatchId(batchId) + " 个");
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public CodeView lookup(String input) {
        SerialCode s = codes.findByCode(SerialCodes.normalize(input)).orElseThrow(() -> BizException.notFound("防伪码"));
        Batch b = inventory.batch(s.getBatchId());
        return new CodeView(s.getCode(), SerialCodes.display(s.getCode()), s.getSeq(), b.getBatchNo(),
                lookup.item(b.getItemId()).getName(), s.getScanCount(), s.getFirstScanAt(), s.getLastScanAt(), s.getCreatedAt());
    }

    // ---------- 消费者扫码 ----------

    @Transactional
    public VerifyView verify(String input) {
        String code = SerialCodes.normalize(input);
        SerialCode s = SerialCodes.wellFormed(code) ? codes.findByCode(code).orElse(null) : null;
        if (s == null) {
            return new VerifyView("NOT_FOUND", "未查到此防伪码", "请核对瓶身或包装上的码是否输入正确。仍查不到的产品可能不是正品，请通过官方渠道购买并联系客服。",
                    SerialCodes.display(code), null, null, null, null, null, null, 0, null);
        }
        LocalDateTime now = time.now();
        s.setScanCount(s.getScanCount() + 1);
        if (s.getFirstScanAt() == null) {
            s.setFirstScanAt(now);
        }
        s.setLastScanAt(now);
        codes.save(s);

        Batch b = inventory.batch(s.getBatchId());
        Item item = lookup.item(b.getItemId());
        String status;
        String title;
        String message;
        boolean recalling = recalls.existsByBatchIdAndStatus(b.getId(), CaseStatus.OPEN);
        if (recalling || b.getStatus() == BatchStatus.FROZEN || b.getStatus() == BatchStatus.REJECTED) {
            status = "RECALLED";
            title = "该批次已暂停销售";
            message = "品牌方正在对该批次进行召回或质量调查，请停止使用，并联系购买渠道或客服办理退换。";
        } else if (b.getStatus() != BatchStatus.RELEASED) {
            status = "UNRELEASED";
            title = "该产品尚未上市";
            message = "这个码对应的批次还没有放行上市，正常渠道不应买到。请联系客服核实。";
        } else if (ShelfLife.isExpired(b.getExpDate(), time.today())) {
            status = "EXPIRED";
            title = "正品，但已过有效期";
            message = "该产品已超过有效期，请勿继续使用。";
        } else if (s.getScanCount() > 1) {
            status = "REPEATED";
            title = "正品，此码已被查询 " + s.getScanCount() + " 次";
            message = "首次查询时间见下方。如果不是您本人查询过，请警惕仿冒（真码可能被复制），建议通过官方渠道购买。";
        } else {
            status = "GENUINE";
            title = "正品，首次查询";
            message = "感谢您选择正品。以下是该产品的批次与效期信息。";
        }
        return new VerifyView(status, title, message, SerialCodes.display(s.getCode()), item.getName(), item.getFilingNo(),
                item.getNetG(), b.getBatchNo(), b.getMfgDate(), b.getExpDate(), s.getScanCount(), s.getFirstScanAt());
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }
}
