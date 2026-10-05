package com.github.lechandonga.tracelogmetricsalertingjava.log;

import com.github.lechandonga.tracelogmetricsalertingjava.tests.BusinessDomain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@BusinessDomain("敏感信息脱敏: 请求头/参数/异常消息")
class SensitiveDataMaskerTest {

    private final SensitiveDataMasker masker = new SensitiveDataMasker();

    @ParameterizedTest
    @ValueSource(strings = {"password", "Password", "user.password", "X-Token", "authorization",
            "api_key", "ACCESS-KEY", "secret", "credentials"})
    @DisplayName("敏感键名(大小写/分隔符变体)整值脱敏")
    void sensitiveKeysMasked(String key) {
        assertEquals(SensitiveDataMasker.MASK, masker.maskValue(key, "super-secret-value"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"username", "name", "orderId", "path"})
    @DisplayName("非敏感键名保留原值")
    void nonSensitiveKeysKept(String key) {
        assertEquals("alice", masker.maskValue(key, "alice"));
    }

    @org.junit.jupiter.api.Test
    @DisplayName("异常消息中的 password=/token:/Bearer/JWT/Basic 均被脱敏")
    void messagePatternsMasked() {
        String message = "login failed password=hunter2, token: abcd-1234, "
                + "Authorization: Bearer eyJhbGciOi.JIUzI1NiJ9.eyJzdWIi.MjBjODY, "
                + "Basic dXNlcjpwYXNz, query api_key=zzzz&user=1";
        String masked = masker.mask(message);
        assertFalse(masked.contains("hunter2"));
        assertFalse(masked.contains("abcd-1234"));
        assertFalse(masked.contains("dXNlcjpwYXNz"));
        assertFalse(masked.contains("zzzz"));
        assertFalse(masked.contains("eyJzdWIi"));
        assertTrue(masked.contains(SensitiveDataMasker.MASK));
        assertTrue(masked.contains("user=1"), "非敏感内容应保留");
    }

    @org.junit.jupiter.api.Test
    @DisplayName("参数表整体脱敏: 敏感值消失, 非敏感值保留")
    void mapMasked() {
        Map<String, String> masked = masker.maskMap(Map.of(
                "username", "bob",
                "password", "p@ssw0rd",
                "refreshToken", "refresh-abc"));
        assertEquals("bob", masked.get("username"));
        assertEquals(SensitiveDataMasker.MASK, masked.get("password"));
        assertEquals(SensitiveDataMasker.MASK, masked.get("refreshToken"));
    }

    @org.junit.jupiter.api.Test
    @DisplayName("无敏感信息时文本不变")
    void cleanTextUntouched() {
        String text = "order 12345 processed normally";
        assertEquals(text, masker.mask(text));
    }
}
