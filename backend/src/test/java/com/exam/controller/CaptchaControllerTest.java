package com.exam.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CaptchaControllerTest {

    private static final String CHARS = "234679ABCDEFGHJKMNPQRTUVWXYZ";

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private CaptchaController controller;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        controller = new CaptchaController(redis);
    }

    @Test
    void verifyAcceptsCodeCaseInsensitivelyAndTrimsInput() {
        when(valueOps.get("captcha:t1")).thenReturn("AB23");

        assertTrue(controller.verify("t1", " ab23 "));
        verify(redis).delete("captcha:t1");
    }

    @Test
    void verifyRejectsWrongCodeButStillConsumesIt() {
        when(valueOps.get("captcha:t2")).thenReturn("AB23");

        assertFalse(controller.verify("t2", "zzzz"));
        verify(redis).delete("captcha:t2");
    }

    @Test
    void verifyReturnsFalseWhenTokenMissingInRedis() {
        when(valueOps.get("captcha:t3")).thenReturn(null);

        assertFalse(controller.verify("t3", "AB23"));
        verify(redis, never()).delete(anyString());
    }

    @Test
    void verifyReturnsFalseForNullOrBlankInputs() {
        assertFalse(controller.verify(null, "AB23"));
        assertFalse(controller.verify("t4", null));
        verify(valueOps, never()).get(anyString());
    }

    @Test
    void captchaStoresFourCharacterCodeWithExpiry() {
        Map<String, String> result = controller.captcha();

        assertEquals(12, result.get("token").length());
        assertTrue(result.get("image").startsWith("data:image/png;base64,"));

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("captcha:" + result.get("token")), code.capture(),
                eq(5L), eq(TimeUnit.MINUTES));
        assertEquals(4, code.getValue().length());
        for (char c : code.getValue().toCharArray()) {
            assertTrue(CHARS.indexOf(c) >= 0, "验证码包含不可识别字符: " + c);
        }
    }
}
