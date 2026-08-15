package com.exam.controller;

import com.exam.testutil.InMemoryStringRedisTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CaptchaVerifyTest {

    private InMemoryStringRedisTemplate redis;
    private CaptchaController controller;

    @BeforeEach
    void setUp() {
        redis = new InMemoryStringRedisTemplate();
        controller = new CaptchaController(redis);
    }

    // ---------- verify 方法 ----------

    @Test
    void verifyCorrectCodeReturnsTrue() {
        redis.valueOps.store.put("captcha:tok-abc", "AB12");

        boolean ok = controller.verify("tok-abc", "AB12");
        assertTrue(ok);
        // 用后即焚：key 被删除
        assertNull(redis.valueOps.store.get("captcha:tok-abc"));
    }

    @Test
    void verifyCaseInsensitive() {
        redis.valueOps.store.put("captcha:tok1", "ABCD");

        assertTrue(controller.verify("tok1", "abcd"));
        // 上一次用后即焚，所以要重新存入
        redis.valueOps.store.put("captcha:tok1", "ABCD");
        assertTrue(controller.verify("tok1", "AbCd"));
    }

    @Test
    void verifyWhitespaceTrimmed() {
        redis.valueOps.store.put("captcha:tok2", "X9Y8");

        assertTrue(controller.verify("tok2", "  X9Y8  "));
    }

    @Test
    void verifyWrongCodeReturnsFalseAndStillDeletes() {
        redis.valueOps.store.put("captcha:tok3", "AAAA");

        assertFalse(controller.verify("tok3", "BBBB"));
        // 输错也要删除 key，防止爆破
        assertNull(redis.valueOps.store.get("captcha:tok3"));
    }

    @Test
    void verifyExpiredTokenReturnsFalse() {
        // 不设置 captcha:expired 的值 → 相当于过期/不存在

        assertFalse(controller.verify("expired", "any"));
        // 没存，就没删
        assertFalse(redis.valueOps.store.containsKey("captcha:expired"));
    }

    @Test
    void verifyNullTokenReturnsFalse() {
        assertFalse(controller.verify(null, "1234"));
    }

    @Test
    void verifyNullCodeReturnsFalse() {
        assertFalse(controller.verify("tok", null));
    }

    @Test
    void verifyBothNullReturnsFalse() {
        assertFalse(controller.verify(null, null));
    }
}
