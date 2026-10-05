package com.github.lechandonga.tracelogmetricsalertingjava.common;

/**
 * 错误类别。三类失败必须可区分，分别映射到不同 HTTP 状态码与错误日志字段。
 */
public enum ErrorCategory {
    /** 参数 / 关联标识校验失败（调用方问题，HTTP 400）。 */
    VALIDATION,
    /** 业务规则拒绝（请求合法但业务不允许，HTTP 422）。 */
    BUSINESS,
    /** 系统内部异常（HTTP 500）。 */
    SYSTEM,
    /** 无错误。 */
    NONE
}
