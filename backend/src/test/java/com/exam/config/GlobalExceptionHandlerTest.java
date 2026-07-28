package com.exam.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    // ==================== 参数校验异常 ====================

    @Test
    void handleValidationSingleFieldError() {
        BindingResult binding = mock(BindingResult.class);
        FieldError fe = new FieldError("loginRequest", "username", "用户名不能为空");
        when(binding.getFieldErrors()).thenReturn(List.of(fe));

        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, binding);
        ResponseEntity<?> resp = handler.handleValidation(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals("username: 用户名不能为空", body.get("error"));
    }

    @Test
    void handleValidationMultipleFieldErrorsConcatenated() {
        BindingResult binding = mock(BindingResult.class);
        FieldError fe1 = new FieldError("r", "username", "不能为空");
        FieldError fe2 = new FieldError("r", "password", "至少8位");
        when(binding.getFieldErrors()).thenReturn(List.of(fe1, fe2));

        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, binding);
        ResponseEntity<?> resp = handler.handleValidation(ex);

        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        String err = (String) body.get("error");
        assertTrue(err.contains("username: 不能为空"));
        assertTrue(err.contains("password: 至少8位"));
        assertTrue(err.contains("; "));
    }

    @Test
    void handleValidationEmptyFieldErrorsHasFallback() {
        BindingResult binding = mock(BindingResult.class);
        when(binding.getFieldErrors()).thenReturn(List.of());

        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, binding);
        ResponseEntity<?> resp = handler.handleValidation(ex);

        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals("请求参数校验失败", body.get("error"));
    }

    // ==================== HTTP 方法不支持 ====================

    @Test
    void handleMethodNotSupported405() {
        HttpRequestMethodNotSupportedException ex =
            new HttpRequestMethodNotSupportedException("DELETE");
        ResponseEntity<?> resp = handler.handleMethodNotSupported(ex);

        assertEquals(405, resp.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals("请求方法不支持: DELETE", body.get("error"));
    }

    // ==================== Content-Type 不支持 ====================

    @Test
    void handleMediaTypeNotSupported415() {
        HttpMediaTypeNotSupportedException ex = new HttpMediaTypeNotSupportedException("no");
        ResponseEntity<?> resp = handler.handleMediaTypeNotSupported(ex);

        assertEquals(415, resp.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertTrue(((String) body.get("error")).contains("application/json"));
    }

    // ==================== 请求体 JSON 格式错误 ====================

    @Test
    void handleMessageNotReadable400() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("bad json");
        ResponseEntity<?> resp = handler.handleMessageNotReadable(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertTrue(((String) body.get("error")).contains("JSON 格式"));
    }

    // ==================== 业务 RuntimeException ====================

    @Test
    void handleRuntimeReturns400WithMessage() {
        RuntimeException ex = new RuntimeException("无权操作该考试");
        ResponseEntity<?> resp = handler.handleRuntime(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals("无权操作该考试", body.get("error"));
    }

    // ==================== 通用异常（服务器内部错误） ====================

    @Test
    void handleGeneralExceptionReturns500() {
        Exception ex = new NullPointerException("something null");
        ResponseEntity<?> resp = handler.handleGeneral(ex);

        assertEquals(500, resp.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        // 服务器内部错误不泄漏堆栈，只有通用提示
        assertEquals("服务器内部错误", body.get("error"));
    }
}
