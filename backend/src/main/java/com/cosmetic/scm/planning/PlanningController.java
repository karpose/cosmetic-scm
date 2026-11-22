package com.cosmetic.scm.planning;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.planning.PlanningService.FgRow;
import com.cosmetic.scm.planning.PlanningService.MaterialReq;
import com.cosmetic.scm.planning.PlanningService.MaterialRow;
import com.cosmetic.scm.planning.PlanningService.PlanParams;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 补货建议：只读计算；生成生产单、采购单仍走原有接口和权限。 */
@RestController
@RequestMapping("/api/planning")
@RequiredArgsConstructor
@Validated
public class PlanningController {

    private final PlanningService planning;
    private final com.cosmetic.scm.common.ShortCache cache;

    @GetMapping("/finished-goods")
    public List<FgRow> finishedGoods(@RequestParam(defaultValue = "90") int windowDays,
                                     @RequestParam(defaultValue = "30") int leadDays,
                                     @RequestParam(defaultValue = "15") int safetyDays,
                                     @RequestParam(defaultValue = "60") int coverDays,
                                     @RequestParam(defaultValue = "false") boolean useForecast,
                                     @RequestParam(defaultValue = "false") boolean fresh) {
        PlanParams params = validParams(windowDays, leadDays, safetyDays, coverDays, useForecast);
        // 页面上点“重新计算”或刚生成单据时必须是最新结果，否则建议量没变，用户可能重复生成
        if (fresh) {
            cache.evict("planning:" + params);
        }
        return cache.get("planning:" + params, () -> planning.finishedGoods(params));
    }

    @PostMapping("/materials")
    public List<MaterialRow> materials(@Valid @RequestBody MaterialReq req) {
        return planning.materials(req);
    }

    @GetMapping("/kitting")
    public List<PlanningService.KittingOrderRow> kitting() {
        return planning.kittingCheck();
    }

    private static PlanParams validParams(int windowDays, int leadDays, int safetyDays, int coverDays, boolean useForecast) {
        if (windowDays < 7 || windowDays > 365 || leadDays < 0 || leadDays > 180 || safetyDays < 0 || safetyDays > 180
                || coverDays < 0 || coverDays > 365) {
            throw new com.cosmetic.scm.common.BizException("参数超出范围：观察 7–365 天，交付周期和安全天数 0–180 天，覆盖 0–365 天");
        }
        return new PlanParams(windowDays, leadDays, safetyDays, coverDays, useForecast);
    }
}
