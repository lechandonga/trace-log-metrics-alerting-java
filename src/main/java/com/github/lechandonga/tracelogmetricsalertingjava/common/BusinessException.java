package com.github.lechandonga.tracelogmetricsalertingjava.common;

/** 业务拒绝异常：请求格式合法，但因业务规则不允许而被拒绝。 */
public class BusinessException extends RuntimeException {
    private final String code;

    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
