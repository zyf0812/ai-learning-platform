package com.exam.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // --- Validation errors ---

    @Test
    void handleValidationSingleFieldError() {
        FieldError fe = new FieldError("user", "username", "用户名不能为空");
        BindingResult br = mock(BindingResult.class);
        when(br.getFieldErrors()).thenReturn(List.of(fe));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, br);

        ResponseEntity<?> resp = handler.handleValidation(ex);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) resp.getBody();
        assertEquals("username: 用户名不能为空", body.get("error"));
    }

    @Test
    void handleValidationMultipleFieldErrorsJoined() {
        FieldError f1 = new FieldError("o", "email", "邮箱格式错误");
        FieldError f2 = new FieldError("o", "age", "年龄必须为正整数");
        BindingResult br = mock(BindingResult.class);
        when(br.getFieldErrors()).thenReturn(List.of(f1, f2));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, br);

        ResponseEntity<?> resp = handler.handleValidation(ex);
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) resp.getBody();
        assertTrue(body.get("error").contains("email"));
        assertTrue(body.get("error").contains("age"));
        assertTrue(body.get("error").contains(";"));
    }

    // --- Method not supported ---

    @Test
    void handleMethodNotSupportedReturns405() {
        HttpRequestMethodNotSupportedException ex =
            new HttpRequestMethodNotSupportedException("DELETE");

        ResponseEntity<?> resp = handler.handleMethodNotSupported(ex);
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) resp.getBody();
        assertTrue(body.get("error").contains("DELETE"));
    }

    // --- Media type not supported ---

    @Test
    void handleMediaTypeNotSupportedReturns415() {
        HttpMediaTypeNotSupportedException ex = new HttpMediaTypeNotSupportedException("xml not allowed");

        ResponseEntity<?> resp = handler.handleMediaTypeNotSupported(ex);
        assertEquals(415, resp.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) resp.getBody();
        assertTrue(body.get("error").contains("application/json"));
    }

    // --- Unreadable message (malformed JSON) ---

    @Test
    void handleMessageNotReadableReturns400() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("bad json");

        ResponseEntity<?> resp = handler.handleMessageNotReadable(ex);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) resp.getBody();
        assertTrue(body.get("error").contains("JSON"));
    }

    // --- RuntimeException -> 400 ---

    @Test
    void handleRuntimeExceptionReturns400WithMessage() {
        RuntimeException ex = new RuntimeException("用户名已存在");

        ResponseEntity<?> resp = handler.handleRuntime(ex);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) resp.getBody();
        assertEquals("用户名已存在", body.get("error"));
    }

    // --- General exception -> 500 with generic message (no leak) ---

    @Test
    void handleGeneralExceptionReturns500WithSafeMessage() {
        Exception ex = new Exception("db connection pool exhausted - sensitive detail");

        ResponseEntity<?> resp = handler.handleGeneral(ex);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) resp.getBody();
        // Must NOT leak the raw exception message to client
        assertEquals("服务器内部错误", body.get("error"));
        assertNotEquals("db connection pool exhausted - sensitive detail", body.get("error"));
    }
}
