package com.exam.controller;

import com.exam.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
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

    // ===== register: 验证码通过 → 正常注册 =====

    @Test
    void registerReturnsToken() throws Exception {
        when(captchaController.verify("tok1", "1234")).thenReturn(true);
        when(authService.register("newuser", "pass123", "user"))
            .thenReturn(Map.of("token", "mock-jwt-token", "user", Map.of("role", "user")));

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "username", "newuser",
                    "password", "pass123",
                    "captchaToken", "tok1",
                    "captchaCode", "1234"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").value("mock-jwt-token"));
    }

    // ===== register: 验证码失败 → 不调用 authService =====

    @Test
    void registerWrongCaptchaReturns400BeforeAuth() throws Exception {
        when(captchaController.verify("tok2", "9999")).thenReturn(false);

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "username", "someuser",
                    "password", "somepass",
                    "captchaToken", "tok2",
                    "captchaCode", "9999"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("验证码错误或已过期"));
        // 必须不调用 authService.register
        verify(authService, never()).register(anyString(), anyString(), anyString());
    }

    // ===== register: 用户名重复 → 400 =====

    @Test
    void registerDuplicateReturns400() throws Exception {
        when(captchaController.verify("tok3", "abcd")).thenReturn(true);
        when(authService.register("dupuser", "pass123", "user"))
            .thenThrow(new RuntimeException("用户名已存在"));

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "username", "dupuser",
                    "password", "pass123",
                    "captchaToken", "tok3",
                    "captchaCode", "abcd"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("用户名已存在"));
    }

    // ===== register: admin role 透传 =====

    @Test
    void registerWithAdminRolePassesRoleToService() throws Exception {
        when(captchaController.verify("tok4", "1111")).thenReturn(true);
        when(authService.register("admincan", "adminpass1", "admin"))
            .thenReturn(Map.of("message", "管理员申请已提交，请等待 root 审批"));

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "username", "admincan",
                    "password", "adminpass1",
                    "role", "admin",
                    "captchaToken", "tok4",
                    "captchaCode", "1111"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("管理员申请已提交，请等待 root 审批"));
    }

    // ===== login =====

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

    // ===== /me 端点：带认证 =====

    @Test
    void meReturnsUserInfo() throws Exception {
        // 设置 SecurityContext：模拟通过JWT校验后的用户
        var auth = new UsernamePasswordAuthenticationToken(
            "current-user-id", null,
            List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        var userMap = Map.of(
            "id", "current-user-id",
            "username", "currentuser",
            "role", "user",
            "createdAt", "2025-01-01T00:00:00");
        when(authService.me("current-user-id")).thenReturn(userMap);

        try {
            mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value("current-user-id"))
                .andExpect(jsonPath("$.user.username").value("currentuser"))
                .andExpect(jsonPath("$.user.role").value("user"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // ===== /me 端点：无认证 → Spring Security 默认401/302 =====

    @Test
    void meWithoutAuthReturnsUnauthorized() throws Exception {
        SecurityContextHolder.clearContext();
        // 无 JWT → SecurityContext 无认证 → 受保护端点返回401或重定向
        mockMvc.perform(get("/api/auth/me"))
            .andExpect(status().is4xxClientError());
    }
}
