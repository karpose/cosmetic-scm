package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.cosmetic.scm.audit.AuditLog;
import com.cosmetic.scm.audit.AuditRepository;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.FieldDiff;
import com.cosmetic.scm.domain.ChannelOrderStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.Role;
import com.cosmetic.scm.integration.ChannelOrderRepository;
import com.cosmetic.scm.integration.IntegrationService;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.ItemRepository;
import com.cosmetic.scm.master.MasterDtos;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.PartnerRepository;
import com.cosmetic.scm.security.LoginService;
import com.cosmetic.scm.security.UserRepository;
import com.cosmetic.scm.security.UserService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** 审计日志要能回答“谁、什么时候、把什么从什么改成什么”。不套事务，独立的库，免得影响其它测试的示例数据。 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-audit;MODE=MySQL;DB_CLOSE_DELAY=-1")
class AuditTrailTest {

    @Autowired MasterService master;
    @Autowired ItemRepository items;
    @Autowired PartnerRepository partners;
    @Autowired UserService userService;
    @Autowired UserRepository users;
    @Autowired LoginService login;
    @Autowired AuditRepository audits;
    @Autowired IntegrationService integration;
    @Autowired ChannelOrderRepository channelOrders;

    private void as(String user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private AuditLog latest(String action, String target) {
        return audits.findAllByOrderByIdDesc(PageRequest.of(0, 50)).stream()
                .filter(a -> action.equals(a.getAction()) && target.equals(a.getTarget())).findFirst().orElseThrow();
    }

    @Test
    void fieldDiffListsOnlyWhatChanged() {
        assertThat(FieldDiff.of(Map.of("上限", new BigDecimal("0.5000")), Map.of("上限", new BigDecimal("0.5")))).isEmpty();
        assertThat(FieldDiff.of(Map.of("禁用", false), Map.of("禁用", true))).isEqualTo("禁用 否 → 是");
        var before = new java.util.HashMap<String, Object>();
        before.put("编号", null);
        assertThat(FieldDiff.of(before, Map.of("编号", "A1"))).isEqualTo("编号 空 → A1");
    }

    @Test
    void itemChangeRecordsOldAndNewValues() {
        as("admin");
        Item rm = items.findAll().stream().filter(i -> "RM-GLY-002".equals(i.getCode())).findFirst().orElseThrow();
        master.updateItem(rm.getId(), new MasterDtos.ItemReq(rm.getCode(), rm.getName(), ItemType.RM, rm.getUnit(), rm.getInci(), new BigDecimal("0.5"),
                true, rm.getShelfMonths(), null, null, true, rm.getStdCost(), null));
        String detail = latest("修改物料", rm.getCode()).getDetail();
        assertThat(detail).contains("限用浓度上限 空 → 0.5").contains("禁用 否 → 是");
        assertThat(latest("修改物料", rm.getCode()).getUsername()).isEqualTo("admin");
    }

    @Test
    void partnerChangeRecordsOldAndNewValues() {
        as("admin");
        Partner p = partners.findAll().stream().filter(x -> x.getKind() == PartnerKind.SUPPLIER && x.isQualified()).findFirst().orElseThrow();
        master.updatePartner(p.getId(), new MasterDtos.PartnerReq(p.getCode(), p.getName(), p.getKind(), p.getLicenseNo(), p.getLicenseExpiry(), p.getLicenseScope(),
                p.getBatchPrefix(), p.getChannel(), p.getMinShelfPct(), "新联系人", p.getAddress(), false, p.isCriticalSupplier()));
        assertThat(latest("修改往来单位", p.getCode()).getDetail()).contains("合格名录 是 → 否").contains("联系人").contains("新联系人");
    }

    @Test
    void userChangeRecordsRoleBeforeAndAfter() {
        as("admin");
        var u = users.findByUsername("viewer1").orElseThrow();
        userService.update(u.getId(), new UserService.UpdateUserReq(u.getDisplayName(), true, Set.of(Role.SALES), null, u.getEmail()));
        assertThat(latest("修改账号", "viewer1").getDetail()).contains("角色").contains(Role.VIEWER.getLabel()).contains(Role.SALES.getLabel());
        userService.update(u.getId(), new UserService.UpdateUserReq(u.getDisplayName(), true, Set.of(Role.VIEWER), null, u.getEmail()));
    }

    @Test
    void loginSuccessAndFailureAreRecordedUnderTheAccount() {
        login.login("qa1", "Demo@12345");
        AuditLog ok = latest("登录", "qa1");
        assertThat(ok.getUsername()).isEqualTo("qa1");
        assertThatThrownBy(() -> login.login("qa1", "错误密码")).isInstanceOf(BizException.class);
        AuditLog failed = latest("登录失败", "qa1");
        assertThat(failed.getUsername()).isEqualTo("qa1");
        assertThat(failed.getDetail()).contains("第 1 次");
        login.login("qa1", "Demo@12345");
    }

    @Test
    void manualChannelOrderActionsRecordTheOperator() {
        var failed = channelOrders.findByStatus(ChannelOrderStatus.FAILED).get(0);
        as("sales1");
        integration.retry(failed.getId());
        assertThat(latest("重试电商订单", failed.getPlatformOrderNo()).getUsername()).isEqualTo("sales1");
        integration.cancelOrder(failed.getId(), "系统内取消");
        AuditLog cancelled = latest("取消电商订单", failed.getPlatformOrderNo());
        assertThat(cancelled.getUsername()).isEqualTo("sales1");
        assertThat(cancelled.getReason()).isEqualTo("系统内取消");
    }
}
