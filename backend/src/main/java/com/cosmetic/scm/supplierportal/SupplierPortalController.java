package com.cosmetic.scm.supplierportal;

import java.util.List;

import com.cosmetic.scm.supplierportal.SupplierPortalService.ProfileView;
import com.cosmetic.scm.supplierportal.SupplierPortalService.PromiseReq;
import com.cosmetic.scm.supplierportal.SupplierPortalService.ShipmentReq;
import com.cosmetic.scm.supplierportal.SupplierPortalService.SupplierOrderView;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/portal/supplier")
@RequiredArgsConstructor
public class SupplierPortalController {

    private final SupplierPortalService portal;

    @GetMapping("/orders")
    public List<SupplierOrderView> orders() {
        return portal.myOrders();
    }

    @GetMapping("/profile")
    public ProfileView profile() {
        return portal.profile();
    }

    @PostMapping("/orders/{id}/promise")
    public SupplierOrderView promise(@PathVariable Long id, @Valid @RequestBody PromiseReq req) {
        return portal.promise(id, req);
    }

    @PostMapping("/orders/{id}/shipments")
    public SupplierOrderView ship(@PathVariable Long id, @Valid @RequestBody ShipmentReq req) {
        return portal.createShipment(id, req);
    }

    @PostMapping("/shipments/{id}/cancel")
    public SupplierOrderView cancelShipment(@PathVariable Long id) {
        return portal.cancelShipment(id);
    }
}
