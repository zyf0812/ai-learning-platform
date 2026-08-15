package com.exam.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.MapBindingResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** 构造 BindingResult，不使用 Mockito（避免 Java 25 兼容性问题） */
    private BindingResult bindingResult(List<FieldError> errors) {
        MapBindingResult br = new MapBindingResult(new HashMap<>(), "obj");
        for (FieldError e : errors) br.addError(e);
        return br;
    }

    @Test
    void handleValidationSingleFieldError() {
        FieldError fe = new FieldError("obj", "username", "用户名不能为空");
        MethodArgumentNotValidException ex =
            new MethodArgumentNotValidException(null, bindingResult(List.of(fe)));

        var resp = handler.handleValidation(ex);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        assertEquals("username: 用户名不能为空", body.get("error"));
    }

    @Test
    void handleValidationMultipleFieldsJoinedBySemicolon() {
        FieldError f1 = new FieldError("o", "email", "格式不对");
        FieldError f2 = new FieldError("o", "age", "必须大于0");
        MethodArgumentNotValidException ex =
            new MethodArgumentNotValidException(null, bindingResult(List.of(f1, f2)));

        var resp = handler.handleValidation(ex);
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        assertTrue(body.get("error").contains("email: 格式不对"));
        assertTrue(body.get("error").contains("age: 必须大于0"));
        assertTrue(body.get("error").contains("; "));
    }

    @Test
    void handleMethodNotSupportedReturns405() {
        var ex = new HttpRequestMethodNotSupportedException("DELETE");

        var resp = handler.handleMethodNotSupported(ex);
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        assertTrue(body.get("error").contains("请求方法不支持"));
        assertTrue(body.get("error").contains("DELETE"));
    }

    @Test
    void handleMediaTypeNotSupportedReturns415() {
        var ex = new HttpMediaTypeNotSupportedException("text/plain not allowed");

        var resp = handler.handleMediaTypeNotSupported(ex);
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        assertTrue(body.get("error").contains("不支持的 Content-Type"));
        assertTrue(body.get("error").contains("application/json"));
    }

    @Test
    void handleMessageNotReadableReturns400() {
        var ex = new HttpMessageNotReadableException("bad json");

        var resp = handler.handleMessageNotReadable(ex);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        assertTrue(body.get("error").contains("请求体格式错误"));
        assertTrue(body.get("error").contains("JSON"));
    }

    @Test
    void handleRuntimeReturns400WithMessage() {
        var ex = new RuntimeException("业务逻辑错误信息");

        var resp = handler.handleRuntime(ex);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        assertEquals("业务逻辑错误信息", body.get("error"));
    }

    @Test
    void handleGeneralExceptionReturns500WithSafeMessage() {
        var ex = new NullPointerException("内部崩溃点");

        var resp = handler.handleGeneral(ex);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        assertEquals("服务器内部错误", body.get("error"));
        assertFalse(body.get("error").contains("NullPointerException"));
        assertFalse(body.get("error").contains("内部崩溃点"));
    }
}
