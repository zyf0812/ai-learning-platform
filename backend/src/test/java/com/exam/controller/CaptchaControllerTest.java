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

/**
 * CaptchaController 测试：重点覆盖 verify() 验证码校验逻辑。
 * captcha() 生成接口涉及图片渲染，不做像素级断言。
 */
@ExtendWith(MockitoExtension.class)
class CaptchaControllerTest {

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    private CaptchaController captchaController;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        captchaController = new CaptchaController(redis);
    }

    // ========== verify() 正常匹配 ==========

    @Test
    void verifyExactMatchReturnsTrue() {
        when(valueOps.get("captcha:tok123")).thenReturn("ABCD");

        boolean ok = captchaController.verify("tok123", "ABCD");

        assertTrue(ok);
        verify(redis).delete("captcha:tok123"); // 一次性
    }

    @Test
    void verifyCaseInsensitiveLowercaseMatches() {
        when(valueOps.get("captcha:tok1")).thenReturn("A2C4");

        assertTrue(captchaController.verify("tok1", "a2c4"));
        verify(redis).delete("captcha:tok1");
    }

    @Test
    void verifyCaseInsensitiveUppercaseMatchesLowercaseStored() {
        // 存储时 CHARS 都是大写，所以用户输入小写仍能匹配
        when(valueOps.get("captcha:tok")).thenReturn("WXYZ");

        assertTrue(captchaController.verify("tok", "wxyz"));
    }

    @Test
    void verifyCodeWithWhitespaceTrimmed() {
        when(valueOps.get("captcha:t1")).thenReturn("ABCD");

        assertTrue(captchaController.verify("t1", "  ABCD  "));
        verify(redis).delete("captcha:t1");
    }

    // ========== verify() 不匹配 ==========

    @Test
    void verifyWrongCodeReturnsFalseAndDoesNotDelete() {
        when(valueOps.get("captcha:tok")).thenReturn("ABCD");

        assertFalse(captchaController.verify("tok", "ZZZZ"));
        verify(redis, never()).delete(anyString());
    }

    @Test
    void verifyPartialMatchReturnsFalse() {
        when(valueOps.get("captcha:tok")).thenReturn("ABCD");

        assertFalse(captchaController.verify("tok", "ABC"));
    }

    // ========== verify() 边界空值 ==========

    @Test
    void verifyNullTokenReturnsFalse() {
        boolean result = captchaController.verify(null, "ABCD");
        assertFalse(result);
        verify(valueOps, never()).get(anyString());
    }

    @Test
    void verifyNullCodeReturnsFalse() {
        boolean result = captchaController.verify("tok123", null);
        assertFalse(result);
        verify(valueOps, never()).get(anyString());
    }

    @Test
    void verifyNullStoredReturnsFalse() {
        when(valueOps.get("captcha:expired")).thenReturn(null);

        assertFalse(captchaController.verify("expired", "ANY"));
        verify(redis, never()).delete(anyString());
    }

    // ========== verify() 一次性用后即焚 ==========

    @Test
    void verifySecondCallWithSameTokenFailsBecauseDeleted() {
        // 第一次调用，redis 返回值，调用后 delete
        when(valueOps.get("captcha:onetoken")).thenReturn("ABCD");
        assertTrue(captchaController.verify("onetoken", "ABCD"));

        // 模拟 delete 后 redis 再 get 返回 null
        when(valueOps.get("captcha:onetoken")).thenReturn(null);
        assertFalse(captchaController.verify("onetoken", "ABCD"));
    }

    // ========== captcha() 返回结构 ==========

    @Test
    void captchaReturnsTokenAndImage() {
        doNothing().when(valueOps).set(anyString(), anyString(), anyLong(), eq(TimeUnit.MINUTES));

        Map<String, String> result = captchaController.captcha();

        assertNotNull(result);
        assertTrue(result.containsKey("token"));
        assertTrue(result.containsKey("image"));
        // image 应以 data:image/png;base64, 开头
        assertTrue(result.get("image").startsWith("data:image/png;base64,"));
        // token 不应为空
        assertFalse(result.get("token").isEmpty());

        // Redis 应存入，5 分钟 TTL
        verify(valueOps).set(
            startsWith("captcha:"),
            anyString(),
            eq(5L),
            eq(TimeUnit.MINUTES)
        );
    }

    @Test
    void captchaTokenStoredInRedisWithCorrectKey() {
        doAnswer(inv -> {
            String key = inv.getArgument(0);
            assertTrue(key.startsWith("captcha:"));
            return null;
        }).when(valueOps).set(anyString(), anyString(), anyLong(), any());

        captchaController.captcha();
        verify(valueOps, times(1)).set(anyString(), anyString(), eq(5L), eq(TimeUnit.MINUTES));
    }

    @Test
    void captchaGeneratedCodeIsFourCharsFromAllowedSet() {
        final String[] capturedCode = new String[1];
        doAnswer(inv -> {
            capturedCode[0] = inv.getArgument(1);
            return null;
        }).when(valueOps).set(anyString(), anyString(), anyLong(), any());

        captchaController.captcha();

        assertNotNull(capturedCode[0], "验证码代码应被存入 Redis");
        assertEquals(4, capturedCode[0].length(), "验证码应为4位");
        // 每个字符都应在 CHARS 中：234679ABCDEFGHJKMNPQRTUVWXYZ
        String allowed = "234679ABCDEFGHJKMNPQRTUVWXYZ";
        for (char c : capturedCode[0].toCharArray()) {
            assertTrue(allowed.indexOf(c) >= 0,
                "字符 '" + c + "' 不在允许集合中。实际验证码: " + capturedCode[0]);
        }
    }
}
