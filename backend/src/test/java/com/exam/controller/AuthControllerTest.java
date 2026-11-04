package com.exam.controller;

import com.exam.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@SpringBootTest(properties = {
    "app.jwt.secret=test-secret-key-that-is-long-enough-for-hmac-sha-256!!",
    "app.deepseek.api-key=test",
    "app.zhipu.api-key=test"
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

    @Test
    void registerReturnsToken() throws Exception {
        when(captchaController.verify("captcha-token", "abcd")).thenReturn(true);
        when(authService.register("newuser", "pass123", "user"))
            .thenReturn(Map.of("token", "mock-jwt-token", "user", Map.of("role", "user")));

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "username", "newuser", "password", "pass123",
                    "captchaToken", "captcha-token", "captchaCode", "abcd"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").value("mock-jwt-token"));
    }

    @Test
    void registerDuplicateReturns400() throws Exception {
        when(captchaController.verify("captcha-token", "abcd")).thenReturn(true);
        when(authService.register("dupuser", "pass123", "user"))
            .thenThrow(new RuntimeException("用户名已存在"));

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "username", "dupuser", "password", "pass123",
                    "captchaToken", "captcha-token", "captchaCode", "abcd"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("用户名已存在"));
    }

    @Test
    void registerWithInvalidCaptchaReturns400WithoutCreatingUser() throws Exception {
        when(captchaController.verify("captcha-token", "wrong")).thenReturn(false);

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "username", "newuser", "password", "pass123",
                    "captchaToken", "captcha-token", "captchaCode", "wrong"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("验证码错误或已过期"));

        verify(authService, never()).register(anyString(), anyString(), anyString());
    }

    @Test
    void registerAdminApplicationBypassesTokenAndReturnsPendingMessage() throws Exception {
        when(captchaController.verify("captcha-token", "abcd")).thenReturn(true);
        when(authService.register("root", "pass123", "admin"))
            .thenReturn(Map.of("message", "管理员申请已提交，请等待 root 审批"));

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "username", "root", "password", "pass123", "role", "admin",
                    "captchaToken", "captcha-token", "captchaCode", "abcd"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("管理员申请已提交，请等待 root 审批"));
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
