package com.exam.config;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 全局异常处理器 — 统一的 API 错误响应契约。
 * 确保所有控制器在遇到异常时返回稳定的 {error: "..."} 格式和对应 HTTP 状态码。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleRuntimeReturns400WithMessage() {
        ResponseEntity<?> resp = handler.handleRuntime(new RuntimeException("业务逻辑错误"));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("业务逻辑错误", ((Map<?, ?>) resp.getBody()).get("error"));
    }

    @Test
    void handleGeneralReturns500WithGenericMessage() {
        ResponseEntity<?> resp = handler.handleGeneral(new Exception("unchecked"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertEquals("服务器内部错误", ((Map<?, ?>) resp.getBody()).get("error"));
    }

    @Test
    void handleValidationAggregatesFieldErrors() {
        BindingResult br = mock(BindingResult.class);
        when(br.getFieldErrors()).thenReturn(List.of(
            new FieldError("obj", "username", "用户名不能为空"),
            new FieldError("obj", "password", "密码长度至少8位")
        ));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, br);

        ResponseEntity<?> resp = handler.handleValidation(ex);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        String msg = (String) ((Map<?, ?>) resp.getBody()).get("error");
        assertTrue(msg.contains("username: 用户名不能为空"), "Missing username error: " + msg);
        assertTrue(msg.contains("password: 密码长度至少8位"), "Missing password error: " + msg);
    }

    @Test
    void handleValidationFallsBackToDefaultMessage() {
        BindingResult br = mock(BindingResult.class);
        when(br.getFieldErrors()).thenReturn(List.of());
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, br);

        ResponseEntity<?> resp = handler.handleValidation(ex);
        assertEquals("请求参数校验失败", ((Map<?, ?>) resp.getBody()).get("error"));
    }

    @Test
    void handleMethodNotSupportedReturns405() {
        ResponseEntity<?> resp = handler.handleMethodNotSupported(
            new HttpRequestMethodNotSupportedException("DELETE"));
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, resp.getStatusCode());
        String msg = (String) ((Map<?, ?>) resp.getBody()).get("error");
        assertTrue(msg.contains("DELETE"));
    }

    @Test
    void handleMediaTypeNotSupportedReturns415() {
        ResponseEntity<?> resp = handler.handleMediaTypeNotSupported(
            new HttpMediaTypeNotSupportedException("text/plain not supported"));
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, resp.getStatusCode());
        assertTrue(((Map<?, ?>) resp.getBody()).get("error").toString().contains("application/json"));
    }

    @Test
    void handleMessageNotReadableReturns400JsonHint() {
        ResponseEntity<?> resp = handler.handleMessageNotReadable(
            new HttpMessageNotReadableException("bad json"));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(((Map<?, ?>) resp.getBody()).get("error").toString().contains("JSON"));
    }
}
