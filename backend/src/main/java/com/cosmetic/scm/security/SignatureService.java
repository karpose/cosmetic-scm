package com.cosmetic.scm.security;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.common.BizException;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 电子签名：放行、拒收、冻结等关键签批要求签批人再次输入本人密码，
 * 确保“谁签的”可追溯（《化妆品生产质量管理规范》附 1 电子记录要求）。
 */
@Service
@RequiredArgsConstructor
public class SignatureService {

    private final CurrentUser currentUser;
    private final PasswordEncoder passwordEncoder;

    public AppUser verify(String password) {
        AppUser user = currentUser.get();
        if (password == null || password.isBlank() || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BizException("签批密码不正确");
        }
        return user;
    }
}
