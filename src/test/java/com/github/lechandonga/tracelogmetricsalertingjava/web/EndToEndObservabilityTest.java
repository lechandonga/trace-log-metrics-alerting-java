package com.github.lechandonga.tracelogmetricsalertingjava.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.lechandonga.tracelogmetricsalertingjava.tests.BusinessDomain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@BusinessDomain("端到端: traceId 回写/三类错误/异步传播/日志脱敏")
class EndToEndObservabilityTest {

    @Autowired
    private MockMvc mockMvc;

    private ListAppender<ILoggingEvent> accessAppender;
    private ListAppender<ILoggingEvent> errorAppender;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void attachAppenders() {
        accessAppender = new ListAppender<>();
        errorAppender = new ListAppender<>();
        accessAppender.start();
        errorAppender.start();
        ((Logger) LoggerFactory.getLogger("ACCESS")).addAppender(accessAppender);
        ((Logger) LoggerFactory.getLogger("ERROR")).addAppender(errorAppender);
    }

    @AfterEach
    void detachAppenders() {
        ((Logger) LoggerFactory.getLogger("ACCESS")).detachAppender(accessAppender);
        ((Logger) LoggerFactory.getLogger("ERROR")).detachAppender(errorAppender);
    }

    @Test
    @DisplayName("缺失 traceId 时生成并回写响应头, 访问日志携带同一标识")
    void generatesAndEchoesTraceId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/hello"))
                .andExpect(status().isOk())
                .andReturn();
        String traceId = result.getResponse().getHeader("X-Trace-Id");
        assertNotNull(traceId);
        assertTrue(traceId.startsWith("tl"));
        assertTrue(accessAppender.list.stream().anyMatch(event ->
                event.getFormattedMessage().contains(traceId)
                        && event.getFormattedMessage().contains("\"logType\":\"access\"")));
    }

    @Test
    @DisplayName("合法外部 traceId 原样沿用")
    void acceptsValidIncomingTraceId() throws Exception {
        String external = "0123456789abcdef0123456789abcdef";
        MvcResult result = mockMvc.perform(get("/api/hello").header("X-Trace-Id", external))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(external, result.getResponse().getHeader("X-Trace-Id"));
        assertTrue(result.getResponse().getContentAsString().contains(external));
    }

    @Test
    @DisplayName("非法 traceId 确定性拒绝: 400/VALIDATION/INVALID_TRACE_ID, 不静默接受")
    void rejectsIllegalTraceId() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/hello").header("X-Trace-Id", "BAD-ID!!!"))
                .andExpect(status().isBadRequest())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("VALIDATION"));
        assertTrue(body.contains("INVALID_TRACE_ID"));
        assertEquals("invalid", result.getResponse().getHeader("X-Trace-Id"));
        assertTrue(errorAppender.list.stream().anyMatch(event ->
                event.getFormattedMessage().contains("INVALID_TRACE_ID")));
    }

    @Test
    @DisplayName("三类错误可区分: 400 VALIDATION / 422 BUSINESS / 500 SYSTEM")
    void errorCategoriesDistinguishable() throws Exception {
        mockMvc.perform(get("/api/reject"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/reject").param("orderId", "X1"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/boom"))
                .andExpect(status().isInternalServerError());

        List<ILoggingEvent> errors = errorAppender.list;
        assertTrue(errors.stream().anyMatch(e -> e.getFormattedMessage().contains("\"errorCategory\":\"VALIDATION\"")));
        assertTrue(errors.stream().anyMatch(e -> e.getFormattedMessage().contains("\"errorCategory\":\"BUSINESS\"")));
        assertTrue(errors.stream().anyMatch(e -> e.getFormattedMessage().contains("\"errorCategory\":\"SYSTEM\"")));
    }

    @Test
    @DisplayName("异步请求各阶段与访问日志关联同一 traceId")
    void asyncStagesShareTraceId() throws Exception {
        MvcResult asyncResult = mockMvc.perform(get("/api/hello-async"))
                .andExpect(request().asyncStarted())
                .andReturn();
        String traceId = asyncResult.getResponse().getHeader("X-Trace-Id");
        mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isOk());

        List<ILoggingEvent> stageLogs = accessAppender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("\"logType\":\"stage\"")
                        && (e.getFormattedMessage().contains("async-stage-1")
                        || e.getFormattedMessage().contains("async-stage-2")))
                .toList();
        assertEquals(2, stageLogs.size(), "两个异步阶段均应产生日志");
        stageLogs.forEach(event -> assertTrue(event.getFormattedMessage().contains(traceId)));
        long accessCount = accessAppender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("\"logType\":\"access\"")
                        && e.getFormattedMessage().contains(traceId))
                .count();
        assertEquals(1, accessCount, "每条请求恰好一条访问日志");
    }

    @Test
    @DisplayName("异常消息中的令牌不落日志, 响应体内不泄漏内部细节以外的敏感原文")
    void sensitiveMessageMaskedInLogs() throws Exception {
        mockMvc.perform(get("/api/boom")).andExpect(status().isInternalServerError());
        errorAppender.list.forEach(event ->
                assertFalse(event.getFormattedMessage().contains("abcdef1234567890")));
        assertTrue(errorAppender.list.stream()
                .anyMatch(e -> e.getFormattedMessage().contains("***")));
    }

    @Test
    @DisplayName("请求头与参数中的 Authorization/password/token 不出现在任何日志")
    void sensitiveHeadersAndParamsMasked() throws Exception {
        mockMvc.perform(post("/api/echo")
                        .header("Authorization", "Bearer eyJhbGciOi.JIUzI1NiJ9.signaturepart")
                        .param("username", "alice")
                        .param("password", "hunter2")
                        .param("token", "tok-987654"))
                .andExpect(status().isOk());
        for (ListAppender<ILoggingEvent> appender : List.of(accessAppender, errorAppender)) {
            for (ILoggingEvent event : appender.list) {
                String msg = event.getFormattedMessage();
                assertFalse(msg.contains("hunter2"), "password 泄漏: " + msg);
                assertFalse(msg.contains("tok-987654"), "token 泄漏: " + msg);
                assertFalse(msg.contains("eyJhbGciOi"), "Authorization 泄漏: " + msg);
            }
        }
        assertTrue(accessAppender.list.stream()
                .anyMatch(e -> e.getFormattedMessage().contains("alice")), "非敏感参数应保留");
    }
}
