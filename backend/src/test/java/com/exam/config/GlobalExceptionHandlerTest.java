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

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleValidationReturns400WithFieldErrors() {
        FieldError fe1 = new FieldError("user", "username", "用户名不能为空");
        FieldError fe2 = new FieldError("user", "password", "密码长度至少8位");
        BindingResult br = mock(BindingResult.class);
        when(br.getFieldErrors()).thenReturn(List.of(fe1, fe2));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, br);

        ResponseEntity<?> resp = handler.handleValidation(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) resp.getBody();
        assertNotNull(body);
        String error = body.get("error");
        assertTrue(error.contains("username: 用户名不能为空"));
        assertTrue(error.contains("password: 密码长度至少8位"));
    }

    @Test
    void handleValidationNoFieldErrorsReturnsDefaultMessage() {
        BindingResult br = mock(BindingResult.class);
        when(br.getFieldErrors()).thenReturn(List.of());
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, br);

        ResponseEntity<?> resp = handler.handleValidation(ex);
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) resp.getBody();
        assertEquals("请求参数校验失败", body.get("error"));
    }

    @Test
    void handleMethodNotSupportedReturns405() {
        HttpRequestMethodNotSupportedException ex =
            new HttpRequestMethodNotSupportedException("DELETE");

        ResponseEntity<?> resp = handler.handleMethodNotSupported(ex);

        assertEquals(405, resp.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) resp.getBody();
        assertEquals("请求方法不支持: DELETE", body.get("error"));
    }

    @Test
    void handleMediaTypeNotSupportedReturns415() {
        HttpMediaTypeNotSupportedException ex = new HttpMediaTypeNotSupportedException(
            org.springframework.http.MediaType.TEXT_PLAIN,
            List.of(org.springframework.http.MediaType.APPLICATION_JSON)
        );

        ResponseEntity<?> resp = handler.handleMediaTypeNotSupported(ex);

        assertEquals(415, resp.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) resp.getBody();
        assertTrue(body.get("error").contains("不支持的 Content-Type"));
        assertTrue(body.get("error").contains("application/json"));
    }

    @Test
    void handleMessageNotReadableReturns400JsonFormatError() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("bad json");

        ResponseEntity<?> resp = handler.handleMessageNotReadable(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) resp.getBody();
        assertTrue(body.get("error").contains("请求体格式错误"));
        assertTrue(body.get("error").contains("JSON"));
    }

    @Test
    void handleRuntimeExceptionReturns400WithMessage() {
        RuntimeException ex = new RuntimeException("用户名已存在");

        ResponseEntity<?> resp = handler.handleRuntime(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) resp.getBody();
        assertEquals("用户名已存在", body.get("error"));
    }

    @Test
    void handleAuthLockedRuntimeReturns400WithLockMessage() {
        RuntimeException ex = new RuntimeException("登录尝试过多，请15分钟后再试");

        ResponseEntity<?> resp = handler.handleRuntime(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) resp.getBody();
        assertEquals("登录尝试过多，请15分钟后再试", body.get("error"));
    }

    @Test
    void handleGeneralExceptionReturns500WithGenericMessage() {
        Exception ex = new Exception("database connection refused");

        ResponseEntity<?> resp = handler.handleGeneral(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) resp.getBody();
        // 不应泄露内部异常细节，只返回通用错误
        assertEquals("服务器内部错误", body.get("error"));
        assertNotEquals("database connection refused", body.get("error"));
    }

    @Test
    void handleNullPointerExceptionReturns500NotLeakingDetails() {
        NullPointerException ex = new NullPointerException("something is null");

        ResponseEntity<?> resp = handler.handleGeneral(ex);

        assertEquals(500, resp.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) resp.getBody();
        assertEquals("服务器内部错误", body.get("error"));
    }
}
