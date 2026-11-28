package com.cosmetic.scm.qms;

import java.util.List;

import com.cosmetic.scm.common.BizException;

/**
 * 登记留样时选留样地点。上市放行（{@code QualityService}）和期初导入（{@code OpeningStockService}）共用，只依赖仓库接口，不注入服务。
 * 没有登记留样地点的照旧（留样的地点为空，自查第 15 项提示）；不选的用默认地点，没有默认地点时只有一个在用地点的用它，有多个的要选。
 */
public final class RetainSites {

    private RetainSites() {
    }

    /** 返回加了锁的地点，没有登记地点时返回 null。调用前要先锁住业务主记录（批次），这里再锁地点。 */
    public static RetainSite pick(RetainSiteRepository sites, Long siteId) {
        if (siteId != null) {
            return sites.lockById(siteId).filter(RetainSite::isActive)
                    .orElseThrow(() -> new BizException("留样地点不存在或已停用，请重新选择"));
        }
        List<Long> defaults = sites.findDefaultIds();
        if (defaults.size() == 1) {
            RetainSite d = sites.lockById(defaults.get(0)).orElse(null);
            if (d != null && d.isActive() && d.isDefaultSite()) {
                return d;
            }
        }
        List<Long> ids = sites.findActiveIds();
        if (ids.isEmpty()) {
            return null;
        }
        if (ids.size() > 1) {
            throw new BizException("登记了 " + ids.size() + " 个在用的留样地点，请选择这批留样存放在哪个地点");
        }
        return sites.lockById(ids.get(0)).filter(RetainSite::isActive).orElse(null);
    }

    /** 期初导入用：用默认地点（没有默认地点时只有一个在用地点的用它），否则留空，之后在留样台账里指定。 */
    public static RetainSite defaultOrNull(RetainSiteRepository sites) {
        try {
            return pick(sites, null);
        } catch (BizException e) {
            return null;
        }
    }

    public static String label(RetainSite s) {
        return s == null ? null : s.getName() + "（" + s.getKind().getLabel() + "）";
    }
}
