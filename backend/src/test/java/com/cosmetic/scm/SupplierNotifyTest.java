package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.PoStatus;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.PartnerRepository;
import com.cosmetic.scm.notify.NotificationLogRepository;
import com.cosmetic.scm.notify.NotifyService;
import com.cosmetic.scm.purchase.PurchaseOrder;
import com.cosmetic.scm.purchase.PurchaseOrderRepository;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** 供应商提醒：只含本单位的事；内部没待办也要发；手工发送不打扰供应商；发失败不影响别人。 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"spring.mail.host=smtp.test.invalid", "scm.notify.mail-to=qa@test.invalid"})
@Transactional
class SupplierNotifyTest {

    private static final String SUP_MAIL = "sales@supplier.invalid";

    @Autowired NotifyService notify;
    @Autowired UserRepository users;
    @Autowired PartnerRepository partners;
    @Autowired PurchaseOrderRepository orders;
    @Autowired NotificationLogRepository logs;
    @Autowired TimeProvider time;
    @Autowired com.cosmetic.scm.sourcing.RfqRepository rfqs;
    @Autowired com.cosmetic.scm.sourcing.RfqSupplierRepository invited;
    @MockBean JavaMailSender mail;

    private Partner other;

    @BeforeEach
    void setUp() {
        other = new Partner();
        other.setCode("SUP-OTHER");
        other.setName("另一家供应商");
        other.setKind(PartnerKind.SUPPLIER);
        partners.save(other);
    }

    private AppUser sup1() {
        return users.findByUsername("sup1").orElseThrow();
    }

    private PurchaseOrder po(Long supplier, String no, LocalDate promised, LocalDate expected) {
        PurchaseOrder p = new PurchaseOrder();
        p.setPoNo(no);
        p.setSupplierId(supplier);
        p.setOrderDate(time.today());
        p.setStatus(PoStatus.OPEN);
        p.setCreatedBy("test");
        p.setCreatedAt(LocalDateTime.now());
        p.setExpectedDate(expected);
        if (promised != null) {
            p.setPromisedDate(promised);
            p.setFirstPromisedDate(promised);
            p.setSupplierConfirmedAt(LocalDateTime.now());
        }
        return orders.save(p);
    }

    private List<SimpleMailMessage> sent() {
        ArgumentCaptor<SimpleMailMessage> c = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail, atLeast(0)).send(c.capture());
        return c.getAllValues();
    }

    private SimpleMailMessage to(String address) {
        return sent().stream().filter(m -> List.of(m.getTo()).contains(address)).findFirst().orElse(null);
    }

    @Test
    void supplierGetsOnlyItsOwnConfirmDueAndLicenseItems() {
        AppUser u = sup1();
        u.setEmail(SUP_MAIL);
        LocalDate t = time.today();
        Long id = u.getPartnerId();
        Partner p = partners.findById(id).orElseThrow();
        p.setLicenseExpiry(t.plusDays(10));
        po(id, "PO-NOTIFY-LATE", t.minusDays(1), null);
        po(id, "PO-NOTIFY-SOON", t.plusDays(3), null);
        po(id, "PO-NOTIFY-FAR", t.plusDays(4), null);
        po(id, "PO-NOTIFY-NEW", null, t.plusDays(9));
        po(other.getId(), "PO-OTHER-SUPPLIER", null, t.plusDays(9));

        notify.sendScheduled();

        SimpleMailMessage m = to(SUP_MAIL);
        assertThat(m).isNotNull();
        assertThat(m.getTo()).containsExactly(SUP_MAIL);
        String body = m.getText();
        assertThat(m.getSubject()).startsWith("采购协同提醒").contains("其中 1 项紧急").contains("供应商门户");
        assertThat(body).contains("交期已过").contains("PO-NOTIFY-LATE");
        assertThat(body).contains("交期临近").contains("PO-NOTIFY-SOON");
        assertThat(body).doesNotContain("PO-NOTIFY-FAR");
        assertThat(body).contains("待确认采购单").contains("PO-NOTIFY-NEW").contains("期望到货 " + t.plusDays(9));
        assertThat(body).contains("资质快到期");
        // 紧急的排最前面
        assertThat(body.indexOf("交期已过")).isLessThan(body.indexOf("交期临近"));
        // 别家的单子和内部待办都不能出现
        assertThat(body).doesNotContain("PO-OTHER-SUPPLIER").doesNotContain("物料待检").doesNotContain("投诉").doesNotContain("待上市放行");
    }

    @Test
    void waitingQuotesAreIncludedButQuotedOnesAreNot() {
        AppUser u = sup1();
        u.setEmail(SUP_MAIL);
        com.cosmetic.scm.sourcing.Rfq r = new com.cosmetic.scm.sourcing.Rfq();
        r.setRfqNo("RFQ-NOTIFY-1");
        r.setTitle("待报价测试");
        r.setDeadline(time.today().plusDays(3));
        r.setStatus(com.cosmetic.scm.domain.RfqStatus.OPEN);
        r.setCreatedBy("test");
        r.setCreatedAt(LocalDateTime.now());
        rfqs.save(r);
        com.cosmetic.scm.sourcing.RfqSupplier inv = new com.cosmetic.scm.sourcing.RfqSupplier();
        inv.setRfqId(r.getId());
        inv.setSupplierId(u.getPartnerId());
        invited.save(inv);
        notify.sendScheduled();
        assertThat(to(SUP_MAIL).getText()).contains("待报价询价").contains("RFQ-NOTIFY-1");
        // 示例数据里华澄已经报过的询价不再提醒
        assertThat(to(SUP_MAIL).getText()).doesNotContain("透明质酸钠年度询价");
    }

    @Test
    void manualSendForTestingDoesNotMailSuppliers() {
        AppUser u = sup1();
        u.setEmail(SUP_MAIL);
        po(u.getPartnerId(), "PO-NOTIFY-MANUAL", null, null);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", "x"));
        try {
            notify.sendNow();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
        verify(mail, never()).send(argThat((SimpleMailMessage m) -> m != null && List.of(m.getTo()).contains(SUP_MAIL)));
        // 状态页能看到定时任务会发给谁，发之前心里有数
        NotifyService.Status status = notify.status();
        assertThat(status.suppliers()).extracting(NotifyService.PersonView::email).contains(SUP_MAIL);
    }

    @Test
    void accountsWithoutEmailDisabledOrWithNothingToDoGetNoMail() {
        AppUser u = sup1();
        po(u.getPartnerId(), "PO-NOTIFY-NOMAIL", null, null);
        // 没填邮箱
        notify.sendScheduled();
        assertThat(sent()).noneMatch(m -> m.getSubject().startsWith("采购协同提醒"));
        clearInvocations(mail);
        // 停用
        u.setEmail(SUP_MAIL);
        u.setEnabled(false);
        notify.sendScheduled();
        assertThat(to(SUP_MAIL)).isNull();
    }

    @Test
    void failureIsLoggedAndDoesNotStopTheRest() {
        AppUser u = sup1();
        u.setEmail(SUP_MAIL);
        po(u.getPartnerId(), "PO-NOTIFY-FAIL", null, null);
        doThrow(new MailSendException("连接失败")).when(mail).send(any(SimpleMailMessage.class));
        notify.sendScheduled();
        assertThat(logs.findTop50ByOrderByIdDesc()).anyMatch(l -> SUP_MAIL.equals(l.getRecipient()) && !l.isSuccess() && l.getError().contains("连接失败"));
    }
}
