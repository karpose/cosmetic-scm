package com.cosmetic.scm.security;

import java.util.EnumSet;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.domain.Role;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 库里还没有任何账号时，创建初始管理员。 */
@Component
@Order(1)
@RequiredArgsConstructor
public class BootstrapAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    private final UserRepository users;
    private final UserService userService;

    @Value("${scm.bootstrap-admin.username:admin}")
    private String username;

    @Value("${scm.bootstrap-admin.password:Admin@123456}")
    private String password;

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        userService.newUser(username, "系统管理员", password, EnumSet.of(Role.ADMIN, Role.MASTER_DATA));
        log.warn("已创建初始管理员账号 {}，请登录后立即修改密码", username);
    }
}
