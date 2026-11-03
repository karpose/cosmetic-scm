package com.cosmetic.scm.security;

import java.util.Arrays;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.domain.Role;
import com.cosmetic.scm.security.UserService.ChangePasswordReq;
import com.cosmetic.scm.security.UserService.UserView;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final CurrentUser currentUser;
    private final UserService userService;

    public record LoginReq(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResp(String token, UserView user) {
    }

    public record RoleView(String code, String label) {
    }

    @PostMapping("/login")
    @Transactional
    public LoginResp login(@Valid @RequestBody LoginReq req) {
        AppUser u = users.findByUsername(req.username().trim())
                .filter(AppUser::isEnabled)
                .filter(x -> passwordEncoder.matches(req.password(), x.getPasswordHash()))
                .orElseThrow(() -> new BizException(HttpStatus.UNAUTHORIZED, "账号或密码不正确"));
        return new LoginResp(jwtService.issue(u), UserView.of(u));
    }

    @GetMapping("/me")
    public UserView me() {
        return UserView.of(currentUser.get());
    }

    @PostMapping("/password")
    public void changePassword(@Valid @RequestBody ChangePasswordReq req) {
        userService.changeOwnPassword(req);
    }

    @GetMapping("/roles")
    public List<RoleView> roles() {
        return Arrays.stream(Role.values()).map(r -> new RoleView(r.name(), r.getLabel())).toList();
    }
}
