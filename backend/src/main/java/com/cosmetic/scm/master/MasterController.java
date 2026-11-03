package com.cosmetic.scm.master;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.master.MasterDtos.BomLineView;
import com.cosmetic.scm.master.MasterDtos.BomReq;
import com.cosmetic.scm.master.MasterDtos.BomView;
import com.cosmetic.scm.master.MasterDtos.ItemReq;
import com.cosmetic.scm.master.MasterDtos.PartnerReq;
import com.cosmetic.scm.master.MasterDtos.PartnerView;
import com.cosmetic.scm.master.MasterDtos.WarehouseReq;
import com.cosmetic.scm.master.MasterDtos.WarehouseView;
import com.cosmetic.scm.master.MasterImportService.ImportResult;
import com.cosmetic.scm.master.MasterImportService.ItemImportReq;
import com.cosmetic.scm.master.MasterImportService.PartnerImportReq;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 主数据：所有登录用户可查看，只有主数据管理角色可修改。 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MasterController {

    private static final String CAN_EDIT = "hasAnyRole('ADMIN','MASTER_DATA')";

    private final MasterService master;
    private final MasterImportService importer;

    @GetMapping("/items")
    public List<Item> items() {
        return master.listItems();
    }

    @PostMapping("/items")
    @PreAuthorize(CAN_EDIT)
    public Item createItem(@Valid @RequestBody ItemReq req) {
        return master.createItem(req);
    }

    @PutMapping("/items/{id}")
    @PreAuthorize(CAN_EDIT)
    public Item updateItem(@PathVariable Long id, @Valid @RequestBody ItemReq req) {
        return master.updateItem(id, req);
    }

    @GetMapping("/partners")
    public List<PartnerView> partners() {
        return master.listPartners();
    }

    @PostMapping("/partners")
    @PreAuthorize(CAN_EDIT)
    public PartnerView createPartner(@Valid @RequestBody PartnerReq req) {
        return master.createPartner(req);
    }

    @PutMapping("/partners/{id}")
    @PreAuthorize(CAN_EDIT)
    public PartnerView updatePartner(@PathVariable Long id, @Valid @RequestBody PartnerReq req) {
        return master.updatePartner(id, req);
    }

    @GetMapping("/warehouses")
    public List<WarehouseView> warehouses() {
        return master.listWarehouses();
    }

    @PostMapping("/warehouses")
    @PreAuthorize(CAN_EDIT)
    public WarehouseView createWarehouse(@Valid @RequestBody WarehouseReq req) {
        return master.createWarehouse(req);
    }

    @GetMapping("/boms")
    public List<BomView> boms() {
        return master.listBoms();
    }

    @GetMapping("/boms/{fgItemId}")
    public BomView bom(@PathVariable Long fgItemId) {
        return master.getBom(fgItemId);
    }

    @PostMapping("/boms/{fgItemId}/check")
    public List<BomLineView> checkBom(@PathVariable Long fgItemId, @Valid @RequestBody BomReq req) {
        return master.checkBom(fgItemId, req);
    }

    @PutMapping("/boms/{fgItemId}")
    @PreAuthorize(CAN_EDIT)
    public BomView saveBom(@PathVariable Long fgItemId, @Valid @RequestBody BomReq req) {
        return master.saveBom(fgItemId, req);
    }

    @PostMapping("/import/items")
    @PreAuthorize(CAN_EDIT)
    public ImportResult importItems(@Valid @RequestBody ItemImportReq req) {
        return importer.importItems(req);
    }

    @PostMapping("/import/partners")
    @PreAuthorize(CAN_EDIT)
    public ImportResult importPartners(@Valid @RequestBody PartnerImportReq req) {
        return importer.importPartners(req);
    }
}
