package com.exam.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.APPLICATION_XML;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> bodyOf(ResponseEntity<?> entity) {
        return (Map<String, String>) entity.getBody();
    }

    // --- Validation exceptions ---

    @Test
    void handleValidationSingleFieldError() {
        BindingResult binding = new BeanPropertyBindingResult(new Object(), "target");
        binding.addError(new FieldError("target", "username", "用户名不能为空"));
        MethodArgumentNotValidException ex = makeValidationEx(binding);

        ResponseEntity<?> resp = handler.handleValidation(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("username: 用户名不能为空", bodyOf(resp).get("error"));
    }

    @Test
    void handleValidationMultipleFieldErrors() {
        BindingResult binding = new BeanPropertyBindingResult(new Object(), "target");
        binding.addError(new FieldError("target", "email", "邮箱格式不正确"));
        binding.addError(new FieldError("target", "age", "年龄必须大于0"));
        MethodArgumentNotValidException ex = makeValidationEx(binding);

        ResponseEntity<?> resp = handler.handleValidation(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        String err = bodyOf(resp).get("error");
        assertTrue(err.contains("email: 邮箱格式不正确"), "Should contain email error: " + err);
        assertTrue(err.contains("age: 年龄必须大于0"), "Should contain age error: " + err);
        assertTrue(err.contains("; "), "Should use '; ' separator for multiple errors");
    }

    @Test
    void handleValidationNoFieldErrorsFallsBackMessage() {
        BindingResult binding = new BeanPropertyBindingResult(new Object(), "target");
        // No field errors added
        MethodArgumentNotValidException ex = makeValidationEx(binding);

        ResponseEntity<?> resp = handler.handleValidation(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("请求参数校验失败", bodyOf(resp).get("error"));
    }

    private MethodArgumentNotValidException makeValidationEx(BindingResult binding) {
        MethodParameter param = null;
        try {
            // Create a dummy MethodParameter using any method
            param = new MethodParameter(
                    GlobalExceptionHandler.class.getDeclaredMethod("handleValidation",
                            MethodArgumentNotValidException.class), -1);
        } catch (NoSuchMethodException ignored) {}
        return new MethodArgumentNotValidException(param, binding);
    }

    // --- HTTP method not supported ---

    @Test
    void handleMethodNotSupportedReturns405() {
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("DELETE", List.of("GET", "POST"));

        ResponseEntity<?> resp = handler.handleMethodNotSupported(ex);

        assertEquals(405, resp.getStatusCode().value());
        assertEquals("请求方法不支持: DELETE", bodyOf(resp).get("error"));
    }

    // --- HTTP media type not supported ---

    @Test
    void handleMediaTypeNotSupportedReturns415() {
        HttpMediaTypeNotSupportedException ex =
                new HttpMediaTypeNotSupportedException(APPLICATION_XML, List.of(APPLICATION_JSON));

        ResponseEntity<?> resp = handler.handleMediaTypeNotSupported(ex);

        assertEquals(415, resp.getStatusCode().value());
        assertEquals("不支持的 Content-Type，请使用 application/json", bodyOf(resp).get("error"));
    }

    // --- HTTP message not readable ---

    @Test
    void handleMessageNotReadableReturns400() {
        HttpMessageNotReadableException ex =
                new HttpMessageNotReadableException("Malformed JSON: Unexpected character");

        ResponseEntity<?> resp = handler.handleMessageNotReadable(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("请求体格式错误，请检查 JSON 格式", bodyOf(resp).get("error"));
    }

    // --- RuntimeException (业务异常) ---

    @Test
    void handleRuntimeExceptionReturns400WithMessage() {
        RuntimeException ex = new RuntimeException("用户名已存在");

        ResponseEntity<?> resp = handler.handleRuntime(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("用户名已存在", bodyOf(resp).get("error"));
    }

    @Test
    void handleRuntimeExceptionWithNullMessage() {
        RuntimeException ex = new RuntimeException((String) null);

        ResponseEntity<?> resp = handler.handleRuntime(ex);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertNull(bodyOf(resp).get("error"));  // null message propagates as null
    }

    // --- 兜底 catch-all Exception ---

    @Test
    void handleGeneralExceptionReturns500WithGenericMessage() {
        Exception ex = new NullPointerException("something unexpected null");

        ResponseEntity<?> resp = handler.handleGeneral(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        // Never leak internal details - always generic
        assertEquals("服务器内部错误", bodyOf(resp).get("error"));
    }

    @Test
    void handleGeneralExceptionDoesNotExposeInternalErrorDetail() {
        // Even if error is something very specific like DB error
        Exception ex = new java.sql.SQLException("Connection refused: DB credentials invalid");

        ResponseEntity<?> resp = handler.handleGeneral(ex);

        assertEquals(500, resp.getStatusCode().value());
        assertEquals("服务器内部错误", bodyOf(resp).get("error"));
        assertFalse(bodyOf(resp).values().toString().toLowerCase().contains("credentials"),
                "Must not leak sensitive details");
    }
}
