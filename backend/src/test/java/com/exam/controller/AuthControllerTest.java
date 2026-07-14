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

    @Test
    void registerReturnsToken() throws Exception {
        when(authService.register("newuser", "pass123", "user"))
            .thenReturn(Map.of("token", "mock-jwt-token", "user", Map.of("role", "user")));

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", "newuser", "password", "pass123"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").value("mock-jwt-token"));
    }

    @Test
    void registerDuplicateReturns400() throws Exception {
        when(authService.register("dupuser", "pass123", "user"))
            .thenThrow(new RuntimeException("用户名已存在"));

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", "dupuser", "password", "pass123"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("用户名已存在"));
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

    @Test
    void meReturnsUserInfo() throws Exception {
        when(authService.me("user123"))
            .thenReturn(Map.of("id", "user123", "username", "testuser", "role", "user"));

        mockMvc.perform(get("/api/auth/me")
                .principal(() -> "user123"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user.id").value("user123"))
            .andExpect(jsonPath("$.user.username").value("testuser"))
            .andExpect(jsonPath("$.user.role").value("user"));
    }

    @Test
    void meThrowsWhenUserNotFound() throws Exception {
        when(authService.me("nonexistent"))
            .thenThrow(new RuntimeException("用户不存在"));

        mockMvc.perform(get("/api/auth/me")
                .principal(() -> "nonexistent"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user").doesNotExist());
    }
}
