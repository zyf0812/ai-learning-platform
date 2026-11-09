package com.exam.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CaptchaControllerTest {

    private static final String ALLOWED = "234679ABCDEFGHJKMNPQRTUVWXYZ";

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> ops;

    private CaptchaController controller;

    @BeforeEach
    void setUp() {
        controller = new CaptchaController(redis);
    }

    @Test
    void verifyRejectsNullTokenOrCode() {
        assertFalse(controller.verify(null, "ABCD"));
        assertFalse(controller.verify("token", null));
        verifyNoInteractions(redis);
    }

    @Test
    void verifyRejectsUnknownTokenWithoutConsuming() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("captcha:token")).thenReturn(null);

        assertFalse(controller.verify("token", "ABCD"));
        verify(redis, never()).delete(anyString());
    }

    @Test
    void verifyAcceptsCodeCaseInsensitively() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("captcha:token")).thenReturn("AB2C");

        assertTrue(controller.verify("token", " ab2c "));
        verify(redis).delete("captcha:token");
    }

    @Test
    void verifyRejectsWrongCodeAndStillConsumesToken() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("captcha:token")).thenReturn("AB2C");

        assertFalse(controller.verify("token", "ZZZZ"));
        verify(redis).delete("captcha:token");
    }

    @Test
    void captchaStoresFourCharacterCodeWithFiveMinuteTtl() {
        when(redis.opsForValue()).thenReturn(ops);

        Map<String, String> response = controller.captcha();

        verify(ops).set(startsWith("captcha:"), argThat(code ->
                code.length() == 4 && code.chars().allMatch(c -> ALLOWED.indexOf(c) >= 0)),
                eq(5L), eq(TimeUnit.MINUTES));
        assertEquals(12, response.get("token").length());
        assertTrue(response.get("image").startsWith("data:image/png;base64,"));
    }
}
