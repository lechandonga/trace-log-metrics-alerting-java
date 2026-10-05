package com.github.lechandonga.tracelogmetricsalertingjava.trace;

import com.github.lechandonga.tracelogmetricsalertingjava.common.ApiError;
import com.github.lechandonga.tracelogmetricsalertingjava.common.BusinessException;
import com.github.lechandonga.tracelogmetricsalertingjava.common.ErrorCategory;
import com.github.lechandonga.tracelogmetricsalertingjava.log.StructuredLogger;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;


/**
 * 全局异常处理：参数校验失败（400/VALIDATION）、业务拒绝（422/BUSINESS）、
 * 系统异常（500/SYSTEM）三类明确可区分；异常消息统一脱敏后进入错误日志，
 * 错误类别与 code 回写到 request 属性，供访问日志归类。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    public static final String ERROR_CATEGORY_ATTR = GlobalExceptionHandler.class.getName() + ".category";
    public static final String ERROR_CODE_ATTR = GlobalExceptionHandler.class.getName() + ".code";

    private final StructuredLogger structuredLogger;

    public GlobalExceptionHandler(StructuredLogger structuredLogger) {
        this.structuredLogger = structuredLogger;
    }

    @ExceptionHandler({IllegalArgumentException.class, MissingServletRequestParameterException.class,
            MethodArgumentNotValidException.class})
    public ResponseEntity<ApiError> handleValidation(Exception ex, HttpServletRequest request,
                                                     HttpServletResponse response) {
        String message = ex instanceof MethodArgumentNotValidException methodArgNotValid
                ? firstValidationMessage(methodArgNotValid)
                : ex.getMessage();
        return fail(request, HttpStatus.BAD_REQUEST, ErrorCategory.VALIDATION,
                "VALIDATION_FAILED", message, ex.getClass().getSimpleName());
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex, HttpServletRequest request) {
        return fail(request, HttpStatus.UNPROCESSABLE_ENTITY, ErrorCategory.BUSINESS,
                ex.getCode(), ex.getMessage(), ex.getClass().getSimpleName());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleSystem(Exception ex, HttpServletRequest request) {
        return fail(request, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCategory.SYSTEM,
                "INTERNAL_ERROR", "internal server error: " + ex.getMessage(),
                ex.getClass().getSimpleName());
    }

    private ResponseEntity<ApiError> fail(HttpServletRequest request, HttpStatus httpStatus,
                                          ErrorCategory category, String code, String message,
                                          String exceptionClass) {
        String traceId = TraceContext.get().orElse(TraceIdFilter.INVALID_TRACE_ID);
        request.setAttribute(ERROR_CATEGORY_ATTR, category);
        request.setAttribute(ERROR_CODE_ATTR, code);
        long durationMs = elapsed(request);
        structuredLogger.error(new StructuredLogger.ErrorLogEntry(
                traceId, request.getMethod(), request.getRequestURI(), httpStatus.value(),
                durationMs, category.name(), code, message, exceptionClass));
        return ResponseEntity.status(httpStatus)
                .header(TraceIdFilter.TRACE_ID_HEADER, traceId)
                .body(new ApiError(traceId, category, code, message));
    }

    private String firstValidationMessage(MethodArgumentNotValidException ex) {
        return ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + " " + fieldError.getDefaultMessage())
                .findFirst()
                .orElse("request validation failed");
    }

    private long elapsed(HttpServletRequest request) {
        Object start = request.getAttribute(TraceIdFilter.class.getName() + ".startTime");
        if (start instanceof Long startNanos) {
            return (System.nanoTime() - startNanos) / 1_000_000L;
        }
        return -1L;
    }
}
