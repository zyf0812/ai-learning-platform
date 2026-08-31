package com.exam.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @SuppressWarnings("unchecked")
    private Map<String, String> bodyOf(ResponseEntity<?> re) {
        return (Map<String, String>) re.getBody();
    }

    // ==================== MethodArgumentNotValid: 多字段错误聚合 ====================

    @Test
    void handleValidationAggregatesMultipleFieldErrors() {
        BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Object(), "req");
        br.addError(new FieldError("req", "username", "用户名长度需在3-20之间"));
        br.addError(new FieldError("req", "password", "密码长度至少8位"));
        var ex = new MethodArgumentNotValidException(null, br);

        ResponseEntity<?> re = handler.handleValidation(ex);

        assertEquals(HttpStatus.BAD_REQUEST, re.getStatusCode());
        String msg = bodyOf(re).get("error");
        assertTrue(msg.contains("username"), "消息应包含字段名username");
        assertTrue(msg.contains("password"), "消息应包含字段名password");
        assertTrue(msg.contains("用户名长度需在3-20之间"));
        assertTrue(msg.contains("密码长度至少8位"));
        assertTrue(msg.contains("; "), "多个错误用'; '连接");
    }

    @Test
    void handleValidationSingleFieldError() {
        BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Object(), "req");
        br.addError(new FieldError("req", "captchaCode", "验证码不能为空"));
        var ex = new MethodArgumentNotValidException(null, br);

        ResponseEntity<?> re = handler.handleValidation(ex);
        assertEquals(HttpStatus.BAD_REQUEST, re.getStatusCode());
        assertEquals("captchaCode: 验证码不能为空", bodyOf(re).get("error"));
    }

    @Test
    void handleValidationNoErrorsFallbackMessage() {
        // 理论上不会发生，但 getFieldErrors().stream() 空时走 orElse
        BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Object(), "req");
        var ex = new MethodArgumentNotValidException(null, br);

        ResponseEntity<?> re = handler.handleValidation(ex);
        assertEquals("请求参数校验失败", bodyOf(re).get("error"));
    }

    // ==================== 405 Method Not Allowed ====================

    @Test
    void handleMethodNotSupportedReturns405WithMethodInfo() {
        var ex = new HttpRequestMethodNotSupportedException("PUT");

        ResponseEntity<?> re = handler.handleMethodNotSupported(ex);
        assertEquals(405, re.getStatusCode().value());
        assertTrue(bodyOf(re).get("error").contains("PUT"));
    }

    // ==================== 415 Media Type ====================

    @Test
    void handleMediaTypeNotSupportedReturns415() {
        var ex = mock(HttpMediaTypeNotSupportedException.class);

        ResponseEntity<?> re = handler.handleMediaTypeNotSupported(ex);
        assertEquals(415, re.getStatusCode().value());
        assertEquals("不支持的 Content-Type，请使用 application/json",
            bodyOf(re).get("error"));
    }

    // ==================== 400 JSON 格式错误 ====================

    @Test
    void handleMessageNotReadableReturns400() {
        var ex = mock(HttpMessageNotReadableException.class);

        ResponseEntity<?> re = handler.handleMessageNotReadable(ex);
        assertEquals(HttpStatus.BAD_REQUEST, re.getStatusCode());
        assertEquals("请求体格式错误，请检查 JSON 格式", bodyOf(re).get("error"));
    }

    // ==================== RuntimeException → 400 ====================

    @Test
    void handleRuntimeReturns400WithMessage() {
        ResponseEntity<?> re = handler.handleRuntime(new RuntimeException("用户名已存在"));
        assertEquals(HttpStatus.BAD_REQUEST, re.getStatusCode());
        assertEquals("用户名已存在", bodyOf(re).get("error"));
    }

    // ==================== 通用 Exception → 500，隐藏错误信息 ====================

    @Test
    void handleGeneralExceptionReturns500WithGenericMessage() {
        ResponseEntity<?> re = handler.handleGeneral(new Exception("数据库连接失败 - 敏感信息"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, re.getStatusCode());
        assertEquals("服务器内部错误", bodyOf(re).get("error"));
        // 绝不能泄露内部错误详情
        assertNotEquals("数据库连接失败 - 敏感信息", bodyOf(re).get("error"));
    }
}
