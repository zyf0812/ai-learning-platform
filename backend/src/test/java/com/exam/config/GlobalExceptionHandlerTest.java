package com.exam.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // ===== RuntimeException → 400 =====

    @Test
    void handleRuntimeExceptionReturns400WithMessage() {
        RuntimeException ex = new RuntimeException("业务逻辑出错");
        ResponseEntity<?> resp = handler.handleRuntime(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        assertEquals("业务逻辑出错", body.get("error"));
    }

    @Test
    void handleRuntimeExceptionEmptyMessage() {
        RuntimeException ex = new RuntimeException("");
        ResponseEntity<?> resp = handler.handleRuntime(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        assertEquals("", body.get("error"));
    }

    // ===== 通用 Exception → 500 =====

    @Test
    void handleGeneralExceptionReturns500WithGenericMessage() {
        Exception ex = new Exception("数据库连接失败（不应暴露给用户）");
        ResponseEntity<?> resp = handler.handleGeneral(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        // 必须是通用错误，不应暴露内部细节
        assertEquals("服务器内部错误", body.get("error"));
    }

    // ===== 405 Method Not Supported =====

    @Test
    void handleMethodNotSupportedReturns405() {
        HttpRequestMethodNotSupportedException ex =
            new HttpRequestMethodNotSupportedException("DELETE");
        ResponseEntity<?> resp = handler.handleMethodNotSupported(ex);

        assertEquals(405, resp.getStatusCode().value());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        assertTrue(body.get("error").contains("DELETE"));
    }

    // ===== 415 Media Type Not Supported =====

    @Test
    void handleMediaTypeNotSupportedReturns415() {
        HttpMediaTypeNotSupportedException ex = new HttpMediaTypeNotSupportedException("nope");
        ResponseEntity<?> resp = handler.handleMediaTypeNotSupported(ex);

        assertEquals(415, resp.getStatusCode().value());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        assertTrue(body.get("error").contains("Content-Type"));
    }

    // ===== 400 Message Not Readable (JSON parse error) =====

    @Test
    void handleMessageNotReadableReturns400() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("bad json");
        ResponseEntity<?> resp = handler.handleMessageNotReadable(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        assertTrue(body.get("error").contains("JSON"));
    }

    // ===== Bean Validation 失败 =====

    @Test
    void handleValidationAggregatesFieldErrors() {
        BindingResult binding = mock(BindingResult.class);
        FieldError err1 = new FieldError("login", "username", "用户名不能为空");
        FieldError err2 = new FieldError("login", "password", "密码长度至少8位");
        when(binding.getFieldErrors()).thenReturn(List.of(err1, err2));

        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, binding);
        ResponseEntity<?> resp = handler.handleValidation(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        String error = body.get("error");
        assertTrue(error.contains("username") && error.contains("用户名不能为空"),
            "应包含第一个字段错误: " + error);
        assertTrue(error.contains("password") && error.contains("密码长度至少8位"),
            "应包含第二个字段错误: " + error);
        assertTrue(error.contains("; "), "多个错误用'; '分隔: " + error);
    }

    @Test
    void handleValidationWithEmptyFieldErrorsReturnsFallback() {
        BindingResult binding = mock(BindingResult.class);
        when(binding.getFieldErrors()).thenReturn(List.of());

        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, binding);
        ResponseEntity<?> resp = handler.handleValidation(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        assertEquals("请求参数校验失败", body.get("error"));
    }
}
