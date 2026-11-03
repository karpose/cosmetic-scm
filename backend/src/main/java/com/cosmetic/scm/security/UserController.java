package com.cosmetic.scm.security;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.security.UserService.CreateUserReq;
import com.cosmetic.scm.security.UserService.PasswordReq;
import com.cosmetic.scm.security.UserService.UpdateUserReq;
import com.cosmetic.scm.security.UserService.UserView;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserService userService;

    @GetMapping
    public List<UserView> list() {
        return userService.list();
    }

    @PostMapping
    public UserView create(@Valid @RequestBody CreateUserReq req) {
        return userService.create(req);
    }

    @PutMapping("/{id}")
    public UserView update(@PathVariable Long id, @Valid @RequestBody UpdateUserReq req) {
        return userService.update(id, req);
    }

    @PostMapping("/{id}/reset-password")
    public void resetPassword(@PathVariable Long id, @Valid @RequestBody PasswordReq req) {
        userService.resetPassword(id, req);
    }
}
