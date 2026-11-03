package com.cosmetic.scm.master;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.common.BizException;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 主数据查找。主数据量小（数百到数千行），列表页一次性载入成 Map 做名称映射。
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MasterLookup {

    private final ItemRepository items;
    private final PartnerRepository partners;
    private final WarehouseRepository warehouses;

    public Item item(Long id) {
        if (id == null) {
            throw BizException.notFound("物料");
        }
        return items.findById(id).orElseThrow(() -> BizException.notFound("物料"));
    }

    public Partner partner(Long id) {
        if (id == null) {
            throw BizException.notFound("往来单位");
        }
        return partners.findById(id).orElseThrow(() -> BizException.notFound("往来单位"));
    }

    public Warehouse warehouse(Long id) {
        if (id == null) {
            throw BizException.notFound("仓库");
        }
        return warehouses.findById(id).orElseThrow(() -> BizException.notFound("仓库"));
    }

    public Map<Long, Item> itemMap() {
        return items.findAll().stream().collect(Collectors.toMap(Item::getId, Function.identity()));
    }

    public Map<Long, Partner> partnerMap() {
        return partners.findAll().stream().collect(Collectors.toMap(Partner::getId, Function.identity()));
    }

    public Map<Long, Warehouse> warehouseMap() {
        return warehouses.findAll().stream().collect(Collectors.toMap(Warehouse::getId, Function.identity()));
    }
}
