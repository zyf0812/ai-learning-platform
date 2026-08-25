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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> bodyOf(ResponseEntity<?> resp) {
        assertNotNull(resp);
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody() instanceof Map);
        return (Map<String, String>) resp.getBody();
    }

    // ============ MethodArgumentNotValidException ============

    @Test
    void handleValidationSingleFieldError() {
        FieldError fe = new FieldError("user", "username", "用户名不能为空");
        BindingResult br = mock(BindingResult.class);
        when(br.getFieldErrors()).thenReturn(List.of(fe));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, br);

        ResponseEntity<?> resp = handler.handleValidation(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        Map<String, String> body = bodyOf(resp);
        assertEquals("username: 用户名不能为空", body.get("error"));
    }

    @Test
    void handleValidationMultipleFieldErrorsJoinedBySemicolon() {
        FieldError fe1 = new FieldError("user", "username", "不能为空");
        FieldError fe2 = new FieldError("user", "password", "至少8位");
        BindingResult br = mock(BindingResult.class);
        when(br.getFieldErrors()).thenReturn(List.of(fe1, fe2));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, br);

        ResponseEntity<?> resp = handler.handleValidation(ex);
        Map<String, String> body = bodyOf(resp);
        String err = body.get("error");
        assertTrue(err.contains("username: 不能为空"));
        assertTrue(err.contains("password: 至少8位"));
        assertTrue(err.contains("; "));
    }

    @Test
    void handleValidationEmptyErrorsHasFallbackMessage() {
        BindingResult br = mock(BindingResult.class);
        when(br.getFieldErrors()).thenReturn(List.of());
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, br);

        ResponseEntity<?> resp = handler.handleValidation(ex);
        Map<String, String> body = bodyOf(resp);
        assertEquals("请求参数校验失败", body.get("error"));
    }

    // ============ HttpRequestMethodNotSupportedException ============

    @Test
    void handleMethodNotSupportedReturns405() {
        HttpRequestMethodNotSupportedException ex =
            new HttpRequestMethodNotSupportedException("DELETE");

        ResponseEntity<?> resp = handler.handleMethodNotSupported(ex);

        assertEquals(405, resp.getStatusCode().value());
        Map<String, String> body = bodyOf(resp);
        assertTrue(body.get("error").contains("DELETE"));
        assertTrue(body.get("error").contains("请求方法不支持"));
    }

    // ============ HttpMediaTypeNotSupportedException ============

    @Test
    void handleMediaTypeNotSupportedReturns415() {
        HttpMediaTypeNotSupportedException ex = new HttpMediaTypeNotSupportedException("text/plain not supported");

        ResponseEntity<?> resp = handler.handleMediaTypeNotSupported(ex);

        assertEquals(415, resp.getStatusCode().value());
        Map<String, String> body = bodyOf(resp);
        assertTrue(body.get("error").contains("Content-Type"));
    }

    // ============ HttpMessageNotReadableException ============

    @Test
    void handleMessageNotReadableReturnsBadRequest() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("bad json");

        ResponseEntity<?> resp = handler.handleMessageNotReadable(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        Map<String, String> body = bodyOf(resp);
        assertTrue(body.get("error").contains("JSON 格式"));
    }

    // ============ RuntimeException ============

    @Test
    void handleRuntimeReturnsBadRequestWithMessage() {
        RuntimeException ex = new RuntimeException("业务逻辑错误");

        ResponseEntity<?> resp = handler.handleRuntime(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        Map<String, String> body = bodyOf(resp);
        assertEquals("业务逻辑错误", body.get("error"));
    }

    @Test
    void handleRuntimeAuthFailureUsesExceptionMessage() {
        RuntimeException ex = new RuntimeException("用户名或密码错误");

        ResponseEntity<?> resp = handler.handleRuntime(ex);
        Map<String, String> body = bodyOf(resp);
        assertEquals("用户名或密码错误", body.get("error"));
    }

    // ============ 通用 Exception ============

    @Test
    void handleGeneralExceptionReturns500WithGenericMessage() {
        Exception ex = new NullPointerException("some unexpected NPE");

        ResponseEntity<?> resp = handler.handleGeneral(ex);

        assertEquals(500, resp.getStatusCode().value());
        Map<String, String> body = bodyOf(resp);
        // 不应该泄露内部异常消息
        assertEquals("服务器内部错误", body.get("error"));
        assertNotEquals("some unexpected NPE", body.get("error"));
    }

    @Test
    void handleGeneralDoesNotExposeInternalDetails() {
        Exception ex = new Exception("DB connection refused at ip:10.0.0.5");

        ResponseEntity<?> resp = handler.handleGeneral(ex);
        Map<String, String> body = bodyOf(resp);
        assertFalse(body.get("error").contains("10.0.0.5"));
        assertFalse(body.get("error").contains("connection refused"));
    }
}
