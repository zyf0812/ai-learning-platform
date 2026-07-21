package com.exam.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 覆盖全局异常处理器所有分支，重点为近期新增的 405/415/400 异常路径。
 * 这些处理器是下游广泛的错误处理入口，回归风险高。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void methodNotSupportedReturns405WithMethodName() {
        HttpRequestMethodNotSupportedException e =
                new HttpRequestMethodNotSupportedException("POST");
        ResponseEntity<?> resp = handler.handleMethodNotSupported(e);
        assertEquals(405, resp.getStatusCode().value());
        assertEquals("请求方法不支持: POST", ((Map<?, ?>) resp.getBody()).get("error"));
    }

    @Test
    void mediaTypeNotSupportedReturns415WithJsonHint() {
        HttpMediaTypeNotSupportedException e = mock(HttpMediaTypeNotSupportedException.class);
        ResponseEntity<?> resp = handler.handleMediaTypeNotSupported(e);
        assertEquals(415, resp.getStatusCode().value());
        assertEquals("不支持的 Content-Type，请使用 application/json",
                ((Map<?, ?>) resp.getBody()).get("error"));
    }

    @Test
    void messageNotReadableReturns400WithJsonHint() {
        HttpMessageNotReadableException e = mock(HttpMessageNotReadableException.class);
        ResponseEntity<?> resp = handler.handleMessageNotReadable(e);
        assertEquals(400, resp.getStatusCode().value());
        assertEquals("请求体格式错误，请检查 JSON 格式",
                ((Map<?, ?>) resp.getBody()).get("error"));
    }

    @Test
    void validationErrorJoinsMultipleFieldErrorsWithSemicolon() {
        MethodArgumentNotValidException e = mock(MethodArgumentNotValidException.class);
        BindingResult br = mock(BindingResult.class);
        when(e.getBindingResult()).thenReturn(br);
        when(br.getFieldErrors()).thenReturn(List.of(
                new FieldError("obj", "username", "must not be blank"),
                new FieldError("obj", "password", "size must be 8-50")
        ));
        ResponseEntity<?> resp = handler.handleValidation(e);
        assertEquals(400, resp.getStatusCode().value());
        assertEquals("username: must not be blank; password: size must be 8-50",
                ((Map<?, ?>) resp.getBody()).get("error"));
    }

    @Test
    void validationErrorWithNoFieldErrorsReturnsFallbackMessage() {
        MethodArgumentNotValidException e = mock(MethodArgumentNotValidException.class);
        BindingResult br = mock(BindingResult.class);
        when(e.getBindingResult()).thenReturn(br);
        when(br.getFieldErrors()).thenReturn(List.of());
        ResponseEntity<?> resp = handler.handleValidation(e);
        assertEquals(400, resp.getStatusCode().value());
        assertEquals("请求参数校验失败", ((Map<?, ?>) resp.getBody()).get("error"));
    }

    @Test
    void runtimeExceptionReturns400WithMessage() {
        ResponseEntity<?> resp = handler.handleRuntime(new RuntimeException("boom"));
        assertEquals(400, resp.getStatusCode().value());
        assertEquals("boom", ((Map<?, ?>) resp.getBody()).get("error"));
    }

    @Test
    void generalExceptionReturns500WithGenericMessage() {
        ResponseEntity<?> resp = handler.handleGeneral(new Exception("internal"));
        assertEquals(500, resp.getStatusCode().value());
        assertEquals("服务器内部错误", ((Map<?, ?>) resp.getBody()).get("error"));
    }
}
