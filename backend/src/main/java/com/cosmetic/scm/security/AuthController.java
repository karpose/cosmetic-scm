package com.cosmetic.scm.security;

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

    private final LoginService loginService;
    private final CurrentUser currentUser;
    private final UserService userService;

    public record LoginReq(@NotBlank String username, @NotBlank String password) {
    }

    public record RoleView(String code, String label, boolean portal) {
    }

    @PostMapping("/login")
    public LoginService.LoginResp login(@Valid @RequestBody LoginReq req) {
        return loginService.login(req.username(), req.password());
    }

    @GetMapping("/me")
    public UserView me() {
        return userService.view(currentUser.get());
    }

    @PostMapping("/password")
    public UserView changePassword(@Valid @RequestBody ChangePasswordReq req) {
        return userService.changeOwnPassword(req);
    }

    @GetMapping("/roles")
    public List<RoleView> roles() {
        return Arrays.stream(Role.values()).map(r -> new RoleView(r.name(), r.getLabel(), r.isPortal())).toList();
    }
}
