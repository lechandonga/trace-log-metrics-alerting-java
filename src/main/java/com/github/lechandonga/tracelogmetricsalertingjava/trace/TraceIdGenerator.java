package com.github.lechandonga.tracelogmetricsalertingjava.trace;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * 关联标识生成器：前缀 {@value #PREFIX} + 40 位小写十六进制时间后缀
 * （毫秒时间戳 + 随机串），可与外部合法标识（同为 32~64 位小写十六进制）
 * 区分但共享同一字符集。
 */
public final class TraceIdGenerator {

    public static final String PREFIX = "tl";
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final int RANDOM_BYTES = 8;
    private static final int LENGTH = 40;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        long timestamp = Instant.now().toEpochMilli();
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(LENGTH).append(PREFIX);
        String hexTime = Long.toHexString(timestamp);
        sb.append(hexTime);
        for (byte b : bytes) {
            sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        // 固定总长度，避免时间戳长度随年代变化
        int expected = LENGTH;
        if (sb.length() > expected) {
            sb.setLength(expected);
        }
        while (sb.length() < expected) {
            sb.append('0');
        }
        return sb.toString();
    }
}
