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
 * GlobalExceptionHandler 单元测试
 * 验证每种异常类型到正确的 HTTP 状态码和错误信息映射
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // ========== 参数校验异常 ==========

    @Test
    void handleValidationSingleFieldError() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult br = mock(BindingResult.class);
        when(ex.getBindingResult()).thenReturn(br);
        when(br.getFieldErrors()).thenReturn(List.of(
            new FieldError("obj", "username", "用户名不能为空")
        ));

        ResponseEntity<?> resp = handler.handleValidation(ex);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        assertEquals("username: 用户名不能为空", body.get("error"));
    }

    @Test
    void handleValidationMultipleFieldErrorsJoined() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult br = mock(BindingResult.class);
        when(ex.getBindingResult()).thenReturn(br);
        when(br.getFieldErrors()).thenReturn(List.of(
            new FieldError("obj", "username", "不能为空"),
            new FieldError("obj", "password", "至少8位")
        ));

        ResponseEntity<?> resp = handler.handleValidation(ex);
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        assertTrue(body.get("error").contains("username"));
        assertTrue(body.get("error").contains("password"));
        assertTrue(body.get("error").contains("; "));
    }

    @Test
    void handleValidationNoErrorsFallsBack() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult br = mock(BindingResult.class);
        when(ex.getBindingResult()).thenReturn(br);
        when(br.getFieldErrors()).thenReturn(List.of());

        ResponseEntity<?> resp = handler.handleValidation(ex);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        assertEquals("请求参数校验失败", body.get("error"));
    }

    // ========== 方法不支持 405 ==========

    @Test
    void handleMethodNotSupportedReturns405() {
        var ex = new HttpRequestMethodNotSupportedException("DELETE");
        ResponseEntity<?> resp = handler.handleMethodNotSupported(ex);
        assertEquals(405, resp.getStatusCode().value());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        assertTrue(body.get("error").contains("DELETE"));
        assertTrue(body.get("error").contains("请求方法不支持"));
    }

    // ========== MediaType 不支持 415 ==========

    @Test
    void handleMediaTypeNotSupportedReturns415() {
        var ex = new HttpMediaTypeNotSupportedException("text/plain not supported");
        ResponseEntity<?> resp = handler.handleMediaTypeNotSupported(ex);
        assertEquals(415, resp.getStatusCode().value());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        assertTrue(body.get("error").contains("Content-Type"));
    }

    // ========== 请求体不可读 400 ==========

    @Test
    void handleMessageNotReadableReturns400() {
        HttpMessageNotReadableException ex = mock(HttpMessageNotReadableException.class);
        ResponseEntity<?> resp = handler.handleMessageNotReadable(ex);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        assertTrue(body.get("error").contains("JSON 格式"));
    }

    // ========== RuntimeException → 400 ==========

    @Test
    void handleRuntimeReturns400WithMessage() {
        var ex = new RuntimeException("用户名已存在");
        ResponseEntity<?> resp = handler.handleRuntime(ex);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        assertEquals("用户名已存在", body.get("error"));
    }

    // ========== 通用 Exception → 500，不暴露细节 ==========

    @Test
    void handleGeneralReturns500WithGenericMessage() {
        var ex = new Exception("数据库连接池耗尽 - 敏感细节");
        ResponseEntity<?> resp = handler.handleGeneral(ex);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        var body = (Map<String, String>) resp.getBody();
        // 不应包含敏感错误信息
        assertEquals("服务器内部错误", body.get("error"));
        assertFalse(body.get("error").contains("数据库连接池"));
    }
}
