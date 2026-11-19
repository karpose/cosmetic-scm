package com.cosmetic.scm.integration;

import java.util.List;

import com.cosmetic.scm.domain.FinanceSyncStatus;
import com.cosmetic.scm.integration.FinanceIntegrationService.ApVoucherView;
import com.cosmetic.scm.integration.FinanceIntegrationService.PaymentCallbackReq;
import com.cosmetic.scm.integration.FinanceIntegrationService.PaymentCallbackResult;
import com.cosmetic.scm.integration.FinanceIntegrationService.VoucherAckReq;
import com.cosmetic.scm.settlement.ArSettlementService;
import com.cosmetic.scm.settlement.ArSettlementService.ArReceiptCallbackReq;
import com.cosmetic.scm.settlement.ArSettlementService.ArReceiptCallbackResult;
import com.cosmetic.scm.settlement.ArSettlementService.ArStatementView;
import com.cosmetic.scm.settlement.ArSettlementService.ArVoucherAckReq;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 给外部财务/ERP 系统调用的对账应付/应收凭证拉取、回执与收付款回调接口，请求头 X-Api-Key 认证（见 IntegrationKeyFilter）。 */
@RestController
@RequestMapping("/api/integration/finance")
@RequiredArgsConstructor
public class FinanceIntegrationController {

    private final FinanceIntegrationService finance;
    private final ArSettlementService ar;

    @GetMapping("/vouchers")
    public List<ApVoucherView> vouchers(@RequestParam(required = false) FinanceSyncStatus syncStatus,
                                        @RequestParam(required = false) Boolean pendingOnly) {
        return finance.vouchers(syncStatus, pendingOnly, true);
    }

    @PostMapping("/vouchers/{statementNo}/ack")
    public ApVoucherView ackVoucher(@PathVariable String statementNo, @Valid @RequestBody VoucherAckReq req) {
        return finance.ackVoucher(statementNo, req);
    }

    @PostMapping("/payments")
    public PaymentCallbackResult paymentCallback(@Valid @RequestBody PaymentCallbackReq req) {
        return finance.paymentCallback(req);
    }

    @GetMapping("/ar-vouchers")
    public List<ArStatementView> arVouchers(@RequestParam(required = false) FinanceSyncStatus syncStatus,
                                            @RequestParam(required = false) Boolean pendingOnly) {
        return ar.vouchers(syncStatus, pendingOnly, true);
    }

    @PostMapping("/ar-vouchers/{statementNo}/ack")
    public ArStatementView ackArVoucher(@PathVariable String statementNo, @Valid @RequestBody ArVoucherAckReq req) {
        return ar.ackVoucher(statementNo, req);
    }

    @PostMapping("/receipts")
    public ArReceiptCallbackResult receiptCallback(@Valid @RequestBody ArReceiptCallbackReq req) {
        return ar.receiptCallback(req);
    }
}
