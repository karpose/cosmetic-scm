package com.cosmetic.scm.common;

import org.springframework.http.HttpStatus;

/** 业务规则不满足时抛出，消息直接展示给用户。 */
public class BizException extends RuntimeException {

    private final HttpStatus status;

    public BizException(String message) {
        this(HttpStatus.BAD_REQUEST, message);
    }

    public BizException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static BizException notFound(String what) {
        return new BizException(HttpStatus.NOT_FOUND, what + "不存在");
    }
}
