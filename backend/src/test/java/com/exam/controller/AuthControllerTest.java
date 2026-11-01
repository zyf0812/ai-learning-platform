package com.exam.controller;

import com.exam.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    private Map<String, String> registerBody(String username, String password) {
        Map<String, String> body = new HashMap<>();
        body.put("username", username);
        body.put("password", password);
        body.put("captchaToken", "captcha-token");
        body.put("captchaCode", "ABCD");
        return body;
    }

    @Test
    void registerReturnsTokenWhenCaptchaValid() throws Exception {
        when(captchaController.verify("captcha-token", "ABCD")).thenReturn(true);
        when(authService.register("newuser", "Passw0rd!", "user"))
            .thenReturn(Map.of("token", "mock-jwt-token", "user", Map.of("role", "user")));

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerBody("newuser", "Passw0rd!"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").value("mock-jwt-token"));

        verify(authService).register("newuser", "Passw0rd!", "user");
    }

    @Test
    void registerWithInvalidCaptchaReturns400WithoutCallingService() throws Exception {
        when(captchaController.verify("captcha-token", "ABCD")).thenReturn(false);

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerBody("newuser", "Passw0rd!"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("验证码错误或已过期"));

        verify(authService, org.mockito.Mockito.never())
            .register(anyString(), anyString(), anyString());
    }

    @Test
    void registerDuplicateReturns400() throws Exception {
        when(captchaController.verify("captcha-token", "ABCD")).thenReturn(true);
        when(authService.register("dupuser", "Passw0rd!", "user"))
            .thenThrow(new RuntimeException("用户名已存在"));

        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerBody("dupuser", "Passw0rd!"))))
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
    void loginWithBlankPasswordFailsValidation() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"testuser\",\"password\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("password: 密码不能为空"));
    }
}
