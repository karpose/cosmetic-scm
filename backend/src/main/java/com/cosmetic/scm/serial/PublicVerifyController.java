package com.cosmetic.scm.serial;


import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.serial.SerialService.VerifyView;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消费者扫码查询，无需登录。用 POST 避免被浏览器预取、缓存而误计扫码次数。
 * 每个 IP 每分钟最多 30 次（只在内存里计数，不保存 IP）。
 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicVerifyController {

    private static final int LIMIT_PER_MINUTE = 30;

    private final SerialService serials;
    private final com.cosmetic.scm.common.ClientRateLimiter limiter = new com.cosmetic.scm.common.ClientRateLimiter(LIMIT_PER_MINUTE);

    public record VerifyReq(@NotBlank @Size(max = 40) String code) {
    }

    @PostMapping("/verify")
    public VerifyView verify(@Valid @RequestBody VerifyReq req, HttpServletRequest http) {
        if (limiter.record(com.cosmetic.scm.common.ClientRateLimiter.clientIp(http))) {
            throw new BizException(HttpStatus.TOO_MANY_REQUESTS, "查询太频繁，请稍后再试");
        }
        return serials.verify(req.code());
    }

}
