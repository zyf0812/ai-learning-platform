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
import static org.mockito.Mockito.*;

/**
 * GlobalExceptionHandler 测试：
 * - 每种异常类型映射到正确的 HTTP 状态码
 * - 正确的 error 字段消息
 * - MethodArgumentNotValid 多字段错误合并
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void runtimeExceptionReturns400WithMessage() {
        ResponseEntity<?> resp = handler.handleRuntime(
            new RuntimeException("用户名已存在"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        assertEquals("用户名已存在", body.get("error"));
    }

    @Test
    void validationSingleFieldError() {
        FieldError fe = new FieldError("loginReq", "username", "不能为空");
        BindingResult br = mock(BindingResult.class);
        when(br.getFieldErrors()).thenReturn(List.of(fe));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, br);

        ResponseEntity<?> resp = handler.handleValidation(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        assertEquals("username: 不能为空", body.get("error"));
    }

    @Test
    void validationMultipleFieldErrorsJoined() {
        FieldError f1 = new FieldError("r", "username", "必填");
        FieldError f2 = new FieldError("r", "password", "至少8位");
        BindingResult br = mock(BindingResult.class);
        when(br.getFieldErrors()).thenReturn(List.of(f1, f2));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, br);

        ResponseEntity<?> resp = handler.handleValidation(ex);

        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        String msg = body.get("error");
        assertTrue(msg.contains("username: 必填"), msg);
        assertTrue(msg.contains("password: 至少8位"), msg);
        assertTrue(msg.contains("; "), msg);
    }

    @Test
    void methodNotSupportedReturns405() {
        ResponseEntity<?> resp = handler.handleMethodNotSupported(
            new HttpRequestMethodNotSupportedException("DELETE"));

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        assertTrue(body.get("error").contains("DELETE"));
    }

    @Test
    void mediaTypeUnsupportedReturns415() {
        ResponseEntity<?> resp = handler.handleMediaTypeNotSupported(
            new HttpMediaTypeNotSupportedException("text/plain not ok"));

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        assertTrue(body.get("error").contains("Content-Type"));
    }

    @Test
    void messageNotReadableBadJsonReturns400() {
        ResponseEntity<?> resp = handler.handleMessageNotReadable(
            new HttpMessageNotReadableException("bad json"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        assertTrue(body.get("error").contains("JSON"));
    }

    @Test
    void generalExceptionReturns500() {
        ResponseEntity<?> resp = handler.handleGeneral(
            new NullPointerException("something blew up"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        assertEquals("服务器内部错误", body.get("error"));
    }
}
