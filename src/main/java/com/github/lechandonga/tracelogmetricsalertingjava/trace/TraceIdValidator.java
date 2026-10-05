package com.github.lechandonga.tracelogmetricsalertingjava.trace;

import java.util.regex.Pattern;

/**
 * 关联标识合法性校验。合法规则：{@code ^[0-9a-z]{32,64}$}（外部透传）
 * 或本服务生成的 {@code tl + 38 位小写十六进制}。非法输入会被确定性拒绝，
 * 不静默接受、不静默替换。
 */
public final class TraceIdValidator {

    private static final Pattern EXTERNAL_PATTERN = Pattern.compile("^[0-9a-f]{32,64}$");
    private static final Pattern GENERATED_PATTERN =
            Pattern.compile("^" + TraceIdGenerator.PREFIX + "[0-9a-f]{38}$");

    private TraceIdValidator() {
    }

    public static boolean isValid(String traceId) {
        if (traceId == null) {
            return false;
        }
        return GENERATED_PATTERN.matcher(traceId).matches()
                || EXTERNAL_PATTERN.matcher(traceId).matches();
    }
}
