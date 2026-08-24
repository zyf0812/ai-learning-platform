package com.exam.controller;

import com.exam.config.GlobalExceptionHandler;
import com.exam.dto.LoginRequest;
import com.exam.dto.RegisterRequest;
import com.exam.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AuthController 单元测试。
 *
 * 采用 MockMvcBuilders.standaloneSetup 方式：
 *   - 不加载 Spring ApplicationContext（避免 Java 25 + MockBean/ByteBuddy 兼容性问题）
 *   - 显式注册 AuthController、GlobalExceptionHandler、JSR-303 Validator
 *   - 仍能覆盖请求路由、参数校验(@Valid)、验证码校验、异常→HTTP 状态映射等关键 Web 行为
 */
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private AuthService authService;

    @Mock
    private CaptchaController captchaController;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        // 使用 JSR-303 Validator 让 @NotBlank/@Size/@Pattern 等生效
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        this.mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController(authService, captchaController))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void registerReturnsToken() throws Exception {
        // 验证码通过
        when(captchaController.verify("tok-123", "ABCD")).thenReturn(true);

        when(authService.register("newuser", "Password1", "user"))
                .thenReturn(Map.of("token", "mock-jwt-token", "user", Map.of("role", "user")));

        RegisterRequest req = new RegisterRequest();
        req.setUsername("newuser");
        req.setPassword("Password1");
        req.setCaptchaToken("tok-123");
        req.setCaptchaCode("ABCD");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("mock-jwt-token"));
    }

    @Test
    void registerCaptchaFailsReturns400() throws Exception {
        // 验证码不通过
        when(captchaController.verify("tok-bad", "WRONG")).thenReturn(false);

        RegisterRequest req = new RegisterRequest();
        req.setUsername("newuser");
        req.setPassword("Password1");
        req.setCaptchaToken("tok-bad");
        req.setCaptchaCode("WRONG");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("验证码错误或已过期"));
    }

    @Test
    void registerDuplicateReturns400() throws Exception {
        when(captchaController.verify("tok-456", "A2B3")).thenReturn(true);

        when(authService.register("dupuser", "Password1", "user"))
                .thenThrow(new RuntimeException("用户名已存在"));

        RegisterRequest req = new RegisterRequest();
        req.setUsername("dupuser");
        req.setPassword("Password1");
        req.setCaptchaToken("tok-456");
        req.setCaptchaCode("A2B3");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("用户名已存在"));
    }

    @Test
    void loginReturnsToken() throws Exception {
        when(authService.login("testuser", "Password1"))
                .thenReturn(Map.of("token", "mock-jwt-token"));

        LoginRequest req = new LoginRequest();
        req.setUsername("testuser");
        req.setPassword("Password1");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("mock-jwt-token"));
    }

    @Test
    void loginWrongPasswordReturns401() throws Exception {
        when(authService.login("testuser", "wrongpass"))
                .thenThrow(new RuntimeException("用户名或密码错误"));

        LoginRequest req = new LoginRequest();
        req.setUsername("testuser");
        req.setPassword("wrongpass");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("用户名或密码错误"));
    }

    @Test
    void loginBlankUsernameReturns400() throws Exception {
        // @Valid 触发 LoginRequest 的 @NotBlank 校验，由 GlobalExceptionHandler 转为 400
        LoginRequest req = new LoginRequest();
        req.setUsername("");
        req.setPassword("Password1");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }
}
