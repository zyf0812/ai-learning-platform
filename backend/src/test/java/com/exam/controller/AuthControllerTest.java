package com.exam.controller;

import com.exam.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 提供测试专用的外部配置，避免依赖运行环境的 JWT/API Key 变量
@SpringBootTest(properties = {
    "app.jwt.secret=test-secret-key-that-is-long-enough-for-hmac-sha-256!!",
    "app.deepseek.api-key=test-key",
    "app.zhipu.api-key=test-key"
})
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @MockBean
    private CaptchaController captchaController;

    @BeforeEach
    void setUp() {
        when(captchaController.verify(anyString(), anyString())).thenReturn(true);
    }

    @Test
    void registerReturnsToken() throws Exception {
        when(authService.register("newuser", "Str0ngPass", "user"))
            .thenReturn(Map.of("token", "mock-jwt-token", "user", Map.of("role", "user")));

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "username", "newuser", "password", "Str0ngPass",
                    "captchaToken", "captcha-token", "captchaCode", "2346"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").value("mock-jwt-token"));
    }

    @Test
    void registerDuplicateReturns400() throws Exception {
        when(authService.register("dupuser", "Str0ngPass", "user"))
            .thenThrow(new RuntimeException("用户名已存在"));

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "username", "dupuser", "password", "Str0ngPass",
                    "captchaToken", "captcha-token", "captchaCode", "2346"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("用户名已存在"));
    }

    @Test
    void registerWithInvalidCaptchaReturns400() throws Exception {
        when(captchaController.verify("bad-token", "9999")).thenReturn(false);

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "username", "newuser", "password", "Str0ngPass",
                    "captchaToken", "bad-token", "captchaCode", "9999"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("验证码错误或已过期"));

        verify(authService, never()).register(anyString(), anyString(), anyString());
    }

    @Test
    void loginReturnsToken() throws Exception {
        when(authService.login("testuser", "correctpass"))
            .thenReturn(Map.of("token", "mock-jwt-token"));

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", "testuser", "password", "correctpass"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").value("mock-jwt-token"));
    }

    @Test
    void loginWrongPasswordReturns401() throws Exception {
        when(authService.login("testuser", "wrongpass"))
            .thenThrow(new RuntimeException("用户名或密码错误"));

        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", "testuser", "password", "wrongpass"))))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("用户名或密码错误"));
    }
}
