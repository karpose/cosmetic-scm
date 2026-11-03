package com.cosmetic.scm.domain.rules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 先到期先出（FEFO）批次分配。
 * 调用方负责只传入已放行的批次；本类负责排除过期批次和剩余效期不达标的批次，
 * 再按有效期从早到晚分配。纯函数，不读写数据库。
 */
public final class FefoAllocator {

    private FefoAllocator() {
    }

    /** 一个可分配的库存位置：某批次在某仓库的可用数量。 */
    public record Candidate(Long batchId, String batchNo, Long warehouseId, BigDecimal available,
                            LocalDate mfgDate, LocalDate expDate) {
    }

    public record Pick(Long batchId, String batchNo, Long warehouseId, BigDecimal qty, LocalDate expDate) {
    }

    /**
     * @param picks       分配结果，按 FEFO 顺序
     * @param shortQty    仍缺少的数量，0 表示足够
     * @param excludedQty 因过期或剩余效期不达标被排除的数量
     */
    public record Result(List<Pick> picks, BigDecimal shortQty, BigDecimal excludedQty) {
        public boolean fulfilled() {
            return shortQty.signum() == 0;
        }
    }

    /**
     * @param candidates 候选库存
     * @param qty        需求数量，必须大于 0
     * @param minRatio   最低剩余效期比例（0~1），例如客户要求不低于 2/3 传 0.67；0 表示只排除已过期
     * @param today      计算效期的基准日
     */
    public static Result allocate(List<Candidate> candidates, BigDecimal qty, double minRatio, LocalDate today) {
        if (qty == null || qty.signum() <= 0) {
            throw new IllegalArgumentException("需求数量必须大于 0");
        }
        BigDecimal excluded = BigDecimal.ZERO;
        List<Candidate> usable = new ArrayList<>();
        for (Candidate c : candidates) {
            if (c.available() == null || c.available().signum() <= 0) {
                continue;
            }
            if (ShelfLife.isExpired(c.expDate(), today)) {
                excluded = excluded.add(c.available());
                continue;
            }
            Double ratio = ShelfLife.remainingRatio(c.mfgDate(), c.expDate(), today);
            if (ratio != null && ratio < minRatio) {
                excluded = excluded.add(c.available());
                continue;
            }
            usable.add(c);
        }
        usable.sort(Comparator
                .comparing(Candidate::expDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Candidate::batchNo));

        BigDecimal need = qty;
        List<Pick> picks = new ArrayList<>();
        for (Candidate c : usable) {
            if (need.signum() <= 0) {
                break;
            }
            BigDecimal take = c.available().min(need);
            picks.add(new Pick(c.batchId(), c.batchNo(), c.warehouseId(), take, c.expDate()));
            need = need.subtract(take);
        }
        return new Result(List.copyOf(picks), need.max(BigDecimal.ZERO), excluded);
    }
}
