package com.exam.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @SuppressWarnings("unchecked")
    private Map<String, String> bodyOf(ResponseEntity<?> entity) {
        return (Map<String, String>) entity.getBody();
    }

    // ==================== MethodArgumentNotValidException ====================

    @Test
    void handleValidation_aggregatesFieldErrors() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult br = mock(BindingResult.class);
        when(ex.getBindingResult()).thenReturn(br);
        when(br.getFieldErrors()).thenReturn(List.of(
            new FieldError("obj", "username", "用户名不能为空"),
            new FieldError("obj", "password", "密码至少8位")
        ));

        ResponseEntity<?> resp = handler.handleValidation(ex);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        String err = bodyOf(resp).get("error");
        assertTrue(err.contains("username: 用户名不能为空"));
        assertTrue(err.contains("password: 密码至少8位"));
    }

    @Test
    void handleValidation_noFieldErrorsFallbackMessage() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult br = mock(BindingResult.class);
        when(ex.getBindingResult()).thenReturn(br);
        when(br.getFieldErrors()).thenReturn(List.of());

        ResponseEntity<?> resp = handler.handleValidation(ex);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("请求参数校验失败", bodyOf(resp).get("error"));
    }

    // ==================== 其他 Spring MVC 异常 ====================

    @Test
    void handleMethodNotSupported_returns405() {
        var ex = new HttpRequestMethodNotSupportedException("DELETE");
        ResponseEntity<?> resp = handler.handleMethodNotSupported(ex);
        assertEquals(405, resp.getStatusCode().value());
        assertTrue(bodyOf(resp).get("error").contains("DELETE"));
        assertTrue(bodyOf(resp).get("error").contains("请求方法不支持"));
    }

    @Test
    void handleMediaTypeNotSupported_returns415() {
        var ex = new HttpMediaTypeNotSupportedException("text/html");
        ResponseEntity<?> resp = handler.handleMediaTypeNotSupported(ex);
        assertEquals(415, resp.getStatusCode().value());
        assertTrue(bodyOf(resp).get("error").contains("application/json"));
    }

    @Test
    void handleMessageNotReadable_returns400BadJson() {
        HttpMessageNotReadableException ex = mock(HttpMessageNotReadableException.class);
        ResponseEntity<?> resp = handler.handleMessageNotReadable(ex);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(bodyOf(resp).get("error").contains("JSON"));
    }

    // ==================== RuntimeException (业务异常) ====================

    @Test
    void handleRuntime_customMessageIn400() {
        var ex = new RuntimeException("用户名已存在");
        ResponseEntity<?> resp = handler.handleRuntime(ex);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("用户名已存在", bodyOf(resp).get("error"));
    }

    @Test
    void handleRuntime_emptyMessageStillWrapped() {
        var ex = new RuntimeException();
        ResponseEntity<?> resp = handler.handleRuntime(ex);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertNotNull(bodyOf(resp));
    }

    // ==================== 通用异常 ====================

    @Test
    void handleGeneral_returns500WithGenericMessage() {
        var ex = new NullPointerException("unexpected NPE");
        ResponseEntity<?> resp = handler.handleGeneral(ex);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        // 不应向客户端暴露内部错误细节
        assertEquals("服务器内部错误", bodyOf(resp).get("error"));
        assertFalse(bodyOf(resp).get("error").contains("NPE"));
    }
}
