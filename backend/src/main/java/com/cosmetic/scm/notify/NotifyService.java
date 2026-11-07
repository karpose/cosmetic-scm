package com.cosmetic.scm.notify;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalDate;
import com.cosmetic.scm.domain.PoStatus;
import com.cosmetic.scm.domain.Role;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.PartnerRepository;
import com.cosmetic.scm.purchase.PurchaseOrder;
import com.cosmetic.scm.purchase.PurchaseOrderRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.dashboard.DashboardService;
import com.cosmetic.scm.dashboard.DashboardService.Todo;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.CurrentUser;
import com.cosmetic.scm.security.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/**
 * 每日待办摘要：把总览里需要处理的事项（警告和严重两级）推送到邮件和企业微信群。
 * 内容就是总览的待办，不另算一套；一个渠道失败不影响另一个，结果写发送记录。
 * 账号填了邮箱的，只收自己岗位的事项（{@link TodoRouting}）；配置的收件人和群收全量。
 * 消息里只有批号、产品和单号这类内部信息，不含消费者联系方式。
 */
@Service
@RequiredArgsConstructor
public class NotifyService {

    private static final Logger log = LoggerFactory.getLogger(NotifyService.class);
    /** 企业微信 markdown 消息的长度上限（字节），超出会被拒收。 */
    private static final int WECOM_MAX_BYTES = 4096;
    private static final Duration WECOM_TIMEOUT = Duration.ofSeconds(10);
    /** 供应商提醒：交期还有几天内开始提醒。 */
    private static final int SUPPLIER_DUE_WARN_DAYS = 3;

    private final DashboardService dashboard;
    private final NotificationLogRepository logs;
    private final UserRepository users;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;
    private final PurchaseOrderRepository purchaseOrders;
    private final PartnerRepository partners;
    private final com.cosmetic.scm.sourcing.RfqService rfqs;
    private final com.cosmetic.scm.settlement.SupplierStatementRepository statements;

    @Value("${scm.notify.enabled:false}")
    private boolean enabled;
    @Value("${scm.notify.cron:}")
    private String cron;
    @Value("${scm.notify.mail-to:}")
    private String mailTo;
    @Value("${scm.notify.wecom-webhook:}")
    private String wecomWebhook;
    @Value("${spring.mail.host:}")
    private String smtpHost;
    @Value("${spring.mail.username:}")
    private String mailFrom;

    public record Digest(String subject, List<Todo> items) {
    }

    /** 今天会收到个人邮件的人。 */
    public record PersonView(String displayName, String email, List<String> roles, int itemCount) {
    }

    public record LogView(Long id, String channel, String recipient, String subject, int itemCount, boolean success, String error,
                          String triggered, LocalDateTime sentAt) {
    }

    /** 不返回 webhook 地址和 SMTP 密码。 */
    public record Status(boolean enabled, String cron, boolean mailConfigured, List<String> mailTo, boolean wecomConfigured,
                         Digest preview, List<PersonView> people, List<PersonView> suppliers, List<LogView> logs) {
    }

    public record SendResult(int sent, int failed, int items) {
    }

    @Transactional(readOnly = true)
    public Digest digest() {
        List<Todo> items = new ArrayList<>(dashboard.summary().todos().stream()
                .filter(t -> "bad".equals(t.level()) || "warn".equals(t.level())).toList());
        items.sort(Comparator.comparingInt(t -> "bad".equals(t.level()) ? 0 : 1));
        return digestOf(items);
    }

    private Digest digestOf(List<Todo> items) {
        long bad = items.stream().filter(t -> "bad".equals(t.level())).count();
        String subject = "供应链系统每日待办 " + time.today() + "：" + items.size() + " 项"
                + (bad > 0 ? "（其中 " + bad + " 项紧急）" : "");
        return new Digest(subject, items);
    }

    /** 填了邮箱的内部账号各自要收的事项。门户账号、停用账号不收；已在全量收件人里的不重复发；没有相关事项的不打扰。 */
    private Map<AppUser, List<Todo>> personal(List<Todo> items) {
        if (smtpHost.isBlank()) {
            return Map.of();
        }
        List<String> full = recipients().stream().map(String::toLowerCase).toList();
        Map<AppUser, List<Todo>> out = new java.util.LinkedHashMap<>();
        for (AppUser u : users.findAll()) {
            if (!u.isEnabled() || u.isPortal() || u.getEmail() == null || full.contains(u.getEmail().toLowerCase())) {
                continue;
            }
            List<Todo> mine = items.stream().filter(t -> TodoRouting.concerns(t.kind(), u.getRoles())).toList();
            if (!mine.isEmpty()) {
                out.put(u, mine);
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Status status() {
        Digest preview = digest();
        List<PersonView> people = personal(preview.items()).entrySet().stream()
                .map(e -> new PersonView(e.getKey().getDisplayName(), e.getKey().getEmail(),
                        e.getKey().getRoles().stream().map(r -> r.getLabel()).sorted().toList(), e.getValue().size())).toList();
        List<PersonView> suppliers = supplierDigests().entrySet().stream()
                .map(e -> new PersonView(e.getKey().getDisplayName(), e.getKey().getEmail(), List.of(Role.SUPPLIER_PORTAL.getLabel()), e.getValue().items().size())).toList();
        return new Status(enabled, cron, mailConfigured(), recipients(), !wecomWebhook.isBlank(), preview, people, suppliers,
                logs.findTop50ByOrderByIdDesc().stream().map(l -> new LogView(l.getId(), l.getChannel(), l.getRecipient(), l.getSubject(),
                        l.getItemCount(), l.isSuccess(), l.getError(), l.getTriggered(), l.getSentAt())).toList());
    }

    /** 定时任务：今天没事就不打扰。供应商的提醒单独判断：内部没有待办时，供应商可能还有单子没确认。 */
    @Transactional
    public void sendScheduled() {
        Digest d = digest();
        if (d.items().isEmpty()) {
            log.info("今天没有需要提醒的事项，不发送");
        } else {
            deliver(d, "schedule");
        }
        for (Map.Entry<AppUser, Digest> e : supplierDigests().entrySet()) {
            String to = e.getKey().getEmail();
            attempt("MAIL", to, e.getValue(), "schedule", () -> sendMail(e.getValue(), List.of(to)));
        }
    }

    /**
     * 供应商账号各自要收的提醒，内容只有本单位的事。门户没有内部待办，这里单独算。
     * 同一单位多个账号各收一份，所以按单位缓存。
     */
    private Map<AppUser, Digest> supplierDigests() {
        if (smtpHost.isBlank()) {
            return Map.of();
        }
        Map<Long, List<Todo>> byPartner = new java.util.HashMap<>();
        Map<AppUser, Digest> out = new java.util.LinkedHashMap<>();
        for (AppUser u : users.findAll()) {
            if (!u.isEnabled() || u.getEmail() == null || u.getPartnerId() == null || !u.getRoles().contains(Role.SUPPLIER_PORTAL)) {
                continue;
            }
            List<Todo> items = byPartner.computeIfAbsent(u.getPartnerId(), this::supplierItems);
            if (!items.isEmpty()) {
                long bad = items.stream().filter(t -> "bad".equals(t.level())).count();
                out.put(u, new Digest("采购协同提醒 " + time.today() + "：" + items.size() + " 项" + (bad > 0 ? "（其中 " + bad + " 项紧急）" : "") + "，请登录供应商门户处理", items));
            }
        }
        return out;
    }

    private List<Todo> supplierItems(Long partnerId) {
        LocalDate today = time.today();
        List<Todo> items = new ArrayList<>();
        Partner p = partners.findById(partnerId).orElse(null);
        if (p != null && p.getLicenseExpiry() != null && !p.getLicenseExpiry().isAfter(today.plusDays(MasterService.LICENSE_WARN_DAYS))) {
            boolean expired = p.getLicenseExpiry().isBefore(today);
            items.add(new Todo("SUP_LICENSE", expired ? "资质已过期" : "资质快到期", expired ? "bad" : "warn", p.getName(),
                    p.getLicenseNo() == null ? "—" : p.getLicenseNo(), "有效期至 " + p.getLicenseExpiry() + "，请上传新证", "/portal/supplier"));
        }
        for (PurchaseOrder po : purchaseOrders.findBySupplierIdAndStatusInOrderById(partnerId, List.of(PoStatus.OPEN, PoStatus.PARTIAL))) {
            if (po.getSupplierConfirmedAt() == null) {
                items.add(new Todo("SUP_CONFIRM", "待确认采购单", "warn", "采购单", po.getPoNo(),
                        (po.getExpectedDate() == null ? "" : "期望到货 " + po.getExpectedDate() + "，") + "请确认并回复交期", "/portal/supplier"));
            } else if (po.getPromisedDate().isBefore(today)) {
                items.add(new Todo("SUP_DUE", "交期已过", "bad", "采购单", po.getPoNo(), "交期 " + po.getPromisedDate() + "，还没到齐", "/portal/supplier"));
            } else if (!po.getPromisedDate().isAfter(today.plusDays(SUPPLIER_DUE_WARN_DAYS))) {
                items.add(new Todo("SUP_DUE", "交期临近", "warn", "采购单", po.getPoNo(), "交期 " + po.getPromisedDate(), "/portal/supplier"));
            }
        }
        for (com.cosmetic.scm.sourcing.Rfq r : rfqs.waitingQuote(partnerId)) {
            items.add(new Todo("SUP_QUOTE", "待报价询价", "warn", r.getTitle(), r.getRfqNo(), "报价截止 " + r.getDeadline(), "/portal/supplier"));
        }
        for (com.cosmetic.scm.settlement.SupplierStatement s : statements.findBySupplierIdAndStatusIn(partnerId, List.of(com.cosmetic.scm.domain.StatementStatus.SENT, com.cosmetic.scm.domain.StatementStatus.CONFIRMED))) {
            boolean sent = s.getStatus() == com.cosmetic.scm.domain.StatementStatus.SENT;
            items.add(new Todo("SUP_STATEMENT", sent ? "待确认对账单" : "对账单待开票", "warn", "对账单", s.getStatementNo(), "合计 " + s.getTotalAmount().toPlainString(), "/portal/supplier"));
        }
        items.sort(Comparator.comparingInt(t -> "bad".equals(t.level()) ? 0 : 1));
        return items;
    }

    /** 管理员手工发送一次，用来验证配置。不发给供应商：点一下测试不该打扰外部单位。 */
    @Transactional
    public SendResult sendNow() {
        if (!mailConfigured() && wecomWebhook.isBlank()) {
            throw new BizException("还没有配置提醒渠道：请设置 SMTP 和收件人（或给账号填邮箱），或企业微信群机器人地址");
        }
        SendResult r = deliver(digest(), currentUser.usernameOrSystem());
        audit.log("手工发送每日待办", "消息提醒", "成功 " + r.sent() + " 个渠道，失败 " + r.failed() + " 个，共 " + r.items() + " 项");
        return r;
    }

    private SendResult deliver(Digest d, String trigger) {
        int sent = 0;
        int failed = 0;
        List<String> full = recipients();
        if (!smtpHost.isBlank() && !full.isEmpty()) {
            if (attempt("MAIL", String.join("，", full), d, trigger, () -> sendMail(d, full))) {
                sent++;
            } else {
                failed++;
            }
        }
        for (Map.Entry<AppUser, List<Todo>> e : personal(d.items()).entrySet()) {
            String to = e.getKey().getEmail();
            Digest mine = digestOf(e.getValue());
            if (attempt("MAIL", to, mine, trigger, () -> sendMail(mine, List.of(to)))) {
                sent++;
            } else {
                failed++;
            }
        }
        if (!wecomWebhook.isBlank()) {
            if (attempt("WECOM", "群机器人", d, trigger, () -> sendWecom(d))) {
                sent++;
            } else {
                failed++;
            }
        }
        return new SendResult(sent, failed, d.items().size());
    }

    private boolean attempt(String channel, String recipient, Digest d, String trigger, Runnable send) {
        NotificationLog l = new NotificationLog();
        l.setChannel(channel);
        l.setRecipient(recipient.length() > 120 ? recipient.substring(0, 120) : recipient);
        l.setSubject(d.subject());
        l.setItemCount(d.items().size());
        l.setTriggered(trigger);
        l.setSentAt(time.now());
        try {
            send.run();
            l.setSuccess(true);
        } catch (RuntimeException e) {
            log.warn("{} 提醒发送失败：{}", channel, safeMessage(e));
            l.setSuccess(false);
            l.setError(safeMessage(e));
        }
        logs.save(l);
        return l.isSuccess();
    }

    private void sendMail(Digest d, List<String> to) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            throw new IllegalStateException("没有可用的邮件服务配置");
        }
        SimpleMailMessage m = new SimpleMailMessage();
        if (!mailFrom.isBlank()) {
            m.setFrom(mailFrom);
        }
        m.setTo(to.toArray(String[]::new));
        m.setSubject(d.subject());
        m.setText(plainText(d));
        sender.send(m);
    }

    private void sendWecom(Digest d) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(WECOM_TIMEOUT);
        factory.setReadTimeout(WECOM_TIMEOUT);
        Map<?, ?> resp = RestClient.builder().requestFactory(factory).build().post().uri(wecomWebhook)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("msgtype", "markdown", "markdown", Map.of("content", wecomMarkdown(d))))
                .retrieve().body(Map.class);
        Object code = resp == null ? null : resp.get("errcode");
        if (!Integer.valueOf(0).equals(code)) {
            throw new IllegalStateException("企业微信返回错误：" + (resp == null ? "空响应" : resp.get("errmsg")));
        }
    }

    static String plainText(Digest d) {
        StringBuilder s = new StringBuilder(d.subject()).append("\n\n");
        if (d.items().isEmpty()) {
            s.append("今天没有需要处理的事项。\n");
        }
        for (Todo t : d.items()) {
            s.append("bad".equals(t.level()) ? "【紧急】" : "【待办】").append(t.label()).append("：").append(t.title())
                    .append("（").append(t.ref()).append("）").append(t.detail()).append("\n");
        }
        return s.toString();
    }

    /** 超长时截断并说明还有多少项，保证消息一定能发出去。 */
    static String wecomMarkdown(Digest d) {
        StringBuilder s = new StringBuilder("**").append(d.subject()).append("**\n");
        if (d.items().isEmpty()) {
            return s.append("今天没有需要处理的事项。").toString();
        }
        int count = 0;
        for (Todo t : d.items()) {
            String line = "> <font color=\"" + ("bad".equals(t.level()) ? "warning" : "comment") + "\">" + t.label() + "</font> "
                    + t.title() + "（" + t.ref() + "）" + t.detail() + "\n";
            String tail = "还有 " + (d.items().size() - count) + " 项，请登录系统查看";
            if (bytes(s) + bytes(line) + bytes(tail) > WECOM_MAX_BYTES) {
                return s.append(tail).toString();
            }
            s.append(line);
            count++;
        }
        return s.toString();
    }

    private static int bytes(CharSequence s) {
        return s.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    private boolean mailConfigured() {
        return !smtpHost.isBlank() && (!recipients().isEmpty()
                || users.findAll().stream().anyMatch(u -> u.isEnabled() && !u.isPortal() && u.getEmail() != null));
    }

    private List<String> recipients() {
        return Arrays.stream(mailTo.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    /** 错误信息要写库、显示给管理员，不能带上群机器人地址（里面的 key 等同密码）。 */
    private String safeMessage(RuntimeException e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (!wecomWebhook.isBlank()) {
            msg = msg.replace(wecomWebhook, "（群机器人地址）");
        }
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }
}
