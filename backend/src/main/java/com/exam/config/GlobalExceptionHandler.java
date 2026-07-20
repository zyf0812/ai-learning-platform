package com.exam.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("请求参数校验失败");
        log.warn("Validation failed: {}", msg);
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<?> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(405).body(Map.of("error", "请求方法不支持: " + e.getMethod()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<?> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(415).body(Map.of("error", "不支持的 Content-Type，请使用 application/json"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleMessageNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "请求体格式错误，请检查 JSON 格式"));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> handleRuntime(RuntimeException e) {
        log.warn("Request failed: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGeneral(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.internalServerError().body(Map.of("error", "服务器内部错误"));
    }
}
