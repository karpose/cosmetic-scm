package com.cosmetic.scm.security;

import com.cosmetic.scm.common.BizException;
import java.util.Arrays;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.domain.Role;
import com.cosmetic.scm.security.UserService.ChangePasswordReq;
import com.cosmetic.scm.security.UserService.UserView;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final int MAX_FAILED_PER_MINUTE = 20;

    private final com.cosmetic.scm.common.ClientRateLimiter failedLogins = new com.cosmetic.scm.common.ClientRateLimiter(MAX_FAILED_PER_MINUTE);
    private final LoginService loginService;
    private final CurrentUser currentUser;
    private final JwtService jwtService;
    private final UserService userService;

    public record LoginReq(@NotBlank String username, @NotBlank String password) {
    }

    public record RoleView(String code, String label, boolean portal) {
    }

    @PostMapping("/login")
    public LoginService.LoginResp login(@Valid @RequestBody LoginReq req, jakarta.servlet.http.HttpServletRequest http) {
        String ip = com.cosmetic.scm.common.ClientRateLimiter.clientIp(http);
        // 账号锁定只防盯着一个账号猜；换着账号试同一个弱密码要按来源限制。只数失败，办公室共用出口 IP 时正常登录不受影响
        if (failedLogins.exceeded(ip)) {
            throw new BizException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "登录失败次数太多，请 1 分钟后再试");
        }
        try {
            return loginService.login(req.username(), req.password());
        } catch (BizException e) {
            failedLogins.record(ip);
            throw e;
        }
    }

    @GetMapping("/me")
    public UserView me() {
        return userService.view(currentUser.get());
    }

    @PostMapping("/password")
    public LoginService.LoginResp changePassword(@Valid @RequestBody ChangePasswordReq req) {
        // 改密后旧令牌全部失效（包括其它设备上的），当前设备换发新令牌，不用重新登录
        UserView view = userService.changeOwnPassword(req);
        return new LoginService.LoginResp(jwtService.issue(currentUser.get()), view);
    }

    @GetMapping("/roles")
    public List<RoleView> roles() {
        return Arrays.stream(Role.values()).map(r -> new RoleView(r.name(), r.getLabel(), r.isPortal())).toList();
    }
}
