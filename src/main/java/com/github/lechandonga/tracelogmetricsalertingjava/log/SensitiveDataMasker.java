package com.github.lechandonga.tracelogmetricsalertingjava.log;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感信息脱敏器。
 * <p>
 * 范围（大小写不敏感，按词根子串匹配）：请求头、请求参数、异常消息中的
 * 令牌 / 密码 / 密钥 / 凭证等字段，以及形如 {@code Authorization: xxx}、
 * {@code password=xxx}、{@code token: xxx} 的内容。脱敏后统一替换为
 * {@value #MASK}，保证敏感原文不出现在任何日志中。
 */
public final class SensitiveDataMasker {

    public static final String MASK = "***";

    private static final Set<String> SENSITIVE_KEYWORDS = Set.of(
            "password", "passwd", "pwd", "secret", "token", "authorization",
            "apikey", "api-key", "api_key", "credential", "credentials",
            "access-key", "accesskey", "private-key", "session");

    /** key=value / key:value 形式，value 取到空白、&、引号或行尾。 */
    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile(
            "(?i)(password|passwd|pwd|secret|token|authorization|api[-_ ]?key|"
                    + "credential(?:s)?|access[-_ ]?key|private[-_ ]?key|session)"
                    + "\\s*[:=]\\s*\"?([^\\s&\"',;]*)");

    /** Bearer/JWT 形态的令牌。 */
    private static final Pattern BEARER_PATTERN = Pattern.compile(
            "(?i)bearer\\s+[A-Za-z0-9._~+/-]+=*");

    /** 单独出现的 JWT（header.payload.signature）。 */
    private static final Pattern JWT_PATTERN = Pattern.compile(
            "\\b[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{5,}\\b");

    /** Basic auth 形态。 */
    private static final Pattern BASIC_PATTERN = Pattern.compile(
            "(?i)basic\\s+[A-Za-z0-9+/=]+");

    public String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = BEARER_PATTERN.matcher(text).replaceAll("Bearer " + MASK);
        result = BASIC_PATTERN.matcher(result).replaceAll("Basic " + MASK);
        Matcher kvMatcher = KEY_VALUE_PATTERN.matcher(result);
        result = kvMatcher.replaceAll(mr -> mr.group(1) + "=" + MASK);
        result = JWT_PATTERN.matcher(result).replaceAll(MASK);
        return result;
    }

    /** 脱敏键值表：键名命中敏感词时整个值替换为 {@value #MASK}，值文本再做模式脱敏。 */
    public String maskValue(String key, String value) {
        if (isSensitiveKey(key)) {
            return MASK;
        }
        return mask(value);
    }

    public Map<String, String> maskMap(Map<String, String> source) {
        java.util.HashMap<String, String> copy = new java.util.LinkedHashMap<>();
        if (source == null) {
            return copy;
        }
        source.forEach((key, value) -> copy.put(key, maskValue(key, value)));
        return copy;
    }

    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase().replace('_', '-');
        for (String keyword : SENSITIVE_KEYWORDS) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
