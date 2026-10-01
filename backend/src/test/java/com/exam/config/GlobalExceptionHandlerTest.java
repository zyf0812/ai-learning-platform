package com.exam.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void runtimeExceptionMapsToBadRequestWithMessage() {
        ResponseEntity<?> resp = handler.handleRuntime(new RuntimeException("用户名已存在"));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals("用户名已存在", ((Map<?, ?>) resp.getBody()).get("error"));
    }

    @Test
    void unexpectedExceptionMapsToInternalServerErrorWithoutLeakingMessage() {
        ResponseEntity<?> resp = handler.handleGeneral(new RuntimeException("DB password leaked"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        // We intentionally do NOT echo back the underlying message to clients.
        assertEquals("服务器内部错误", ((Map<?, ?>) resp.getBody()).get("error"));
    }

    @Test
    void unsupportedHttpMethodMapsTo405() {
        ResponseEntity<?> resp = handler.handleMethodNotSupported(
            new HttpRequestMethodNotSupportedException("DELETE"));
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, resp.getStatusCode());
        assertTrue(((Map<?, ?>) resp.getBody()).get("error").toString().contains("DELETE"));
    }

    @Test
    void unsupportedMediaTypeMapsTo415() {
        ResponseEntity<?> resp = handler.handleMediaTypeNotSupported(
            new HttpMediaTypeNotSupportedException("text/plain"));
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, resp.getStatusCode());
        assertTrue(((Map<?, ?>) resp.getBody()).get("error").toString().contains("application/json"));
    }

    @Test
    void malformedJsonBodyMapsTo400() {
        ResponseEntity<?> resp = handler.handleMessageNotReadable(
            new HttpMessageNotReadableException("bad json", (org.springframework.http.HttpInputMessage) null));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(((Map<?, ?>) resp.getBody()).get("error").toString().contains("JSON"));
    }
}