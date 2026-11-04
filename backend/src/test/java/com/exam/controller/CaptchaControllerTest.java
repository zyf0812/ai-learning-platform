package com.exam.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CaptchaControllerTest {

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> values;

    private CaptchaController controller;

    @BeforeEach
    void setUp() {
        controller = new CaptchaController(redis);
    }

    @Test
    void rejectsMissingTokenOrCodeWithoutTouchingRedis() {
        assertFalse(controller.verify(null, "abcd"));
        assertFalse(controller.verify("token", null));
        verifyNoInteractions(redis);
    }

    @Test
    void rejectsExpiredOrUnknownToken() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("captcha:t1")).thenReturn(null);

        assertFalse(controller.verify("t1", "abcd"));
        verify(redis, never()).delete(anyString());
    }

    @Test
    void acceptsCaseInsensitiveTrimmedCodeAndBurnsToken() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("captcha:t1")).thenReturn("ABCD");

        assertTrue(controller.verify("t1", " abcd "));
        verify(redis).delete("captcha:t1");
    }

    @Test
    void wrongCodeBurnsTokenSoItCannotBeReused() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("captcha:t1")).thenReturn("ABCD");

        assertFalse(controller.verify("t1", "zzzz"));
        verify(redis).delete("captcha:t1");
    }
}
