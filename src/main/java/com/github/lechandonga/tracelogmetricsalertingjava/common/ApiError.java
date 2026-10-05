package com.github.lechandonga.tracelogmetricsalertingjava.common;

/** 统一错误响应体。traceId 永远回写，便于调用方与服务端日志对齐。 */
public record ApiError(String traceId, ErrorCategory category, String code, String message) {
}
