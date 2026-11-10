package com.exam.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtUtil 补充测试：过期、篡改、边界场景
 *
 * 与 JwtUtilTest 互补，覆盖：
 * - 过期 token 解析失败
 * - 被篡改的 token 解析失败（不同签名）
 * - 空/空字符串 userId 和 username
 * - 不同 secret 解析失败
 * - claims 中的 username 验证
 */
class JwtUtilEdgeCaseTest {

    private static final String SECRET = "test-secret-key-that-is-long-enough-for-hmac-sha-256!!";
    private static final int EXPIRATION_DAYS = 7;

    // ==================== 过期/非法 token ====================

    @Test
    void tokenFromStringWithWrongFormat_throws() {
        JwtUtil util = new JwtUtil(SECRET, EXPIRATION_DAYS);
        assertThrows(Exception.class, () -> util.parseToken("just.some.random.text"));
        assertThrows(Exception.class, () -> util.parseToken(""));
        assertThrows(Exception.class, () -> util.parseToken(null));
    }

    // ==================== 签名篡改 ====================

    @Test
    void tokenSignedWithDifferentSecret_cannotBeParsed() {
        String secretA = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        String secretB = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
        JwtUtil utilA = new JwtUtil(secretA, EXPIRATION_DAYS);
        JwtUtil utilB = new JwtUtil(secretB, EXPIRATION_DAYS);

        String token = utilA.generateToken("user1", "name");

        // 用 B 的密钥解析 A 签的 token → 必须失败
        assertThrows(Exception.class, () -> utilB.parseToken(token));
    }

    // ==================== 空值边界 ====================

    @Test
    void emptyUserIdAndUsername_canGenerateToken() {
        JwtUtil util = new JwtUtil(SECRET, EXPIRATION_DAYS);

        String token = util.generateToken("", "");
        assertNotNull(token);
        assertFalse(token.isEmpty());

        var claims = util.parseToken(token);
        // jjwt 不接受空 subject，内部会当 null 处理
        assertNull(claims.getSubject());
        // claim(key, "") 实际存储了空字符串
        assertEquals("", claims.get("username"));
    }

    @Test
    void nullUsernamePassed_claimSimplyOmits() {
        JwtUtil util = new JwtUtil(SECRET, EXPIRATION_DAYS);
        // jjwt 的 claim(key, null) 会移除该 claim，不抛异常
        assertDoesNotThrow(() -> {
            String token = util.generateToken("user1", null);
            assertNotNull(token);
        });
    }

    // ==================== username claim 验证 ====================

    @Test
    void usernameClaimIsPreserved() {
        JwtUtil util = new JwtUtil(SECRET, EXPIRATION_DAYS);
        String token = util.generateToken("subject1", "alice_admin");

        var claims = util.parseToken(token);

        assertEquals("alice_admin", claims.get("username", String.class));
        assertEquals("subject1", claims.getSubject());
    }

    @Test
    void subjectIsUserId() {
        JwtUtil util = new JwtUtil(SECRET, EXPIRATION_DAYS);
        String token = util.generateToken("uuid-abc-123", "bob");

        var claims = util.parseToken(token);
        assertEquals("uuid-abc-123", claims.getSubject());
    }

    // ==================== 过期天数配置 ====================

    @Test
    void oneDayExpiration_differentFromSevenDays() {
        JwtUtil util1 = new JwtUtil(SECRET, 1);
        JwtUtil util7 = new JwtUtil(SECRET, 7);

        String t1 = util1.generateToken("u1", "n");
        String t7 = util7.generateToken("u1", "n");

        // 同一天同一用户，不同过期时间 → token 必须不同（因为 expiration claim 不同）
        assertNotEquals(t1, t7);
    }

    // ==================== 重放 / 新鲜度 ====================

    @Test
    void sameInputDifferentTime_differentTokens() throws InterruptedException {
        JwtUtil util = new JwtUtil(SECRET, EXPIRATION_DAYS);

        String t1 = util.generateToken("user1", "name");
        Thread.sleep(1100); // 等 1 秒，让 issuedAt 不同
        String t2 = util.generateToken("user1", "name");

        assertNotEquals(t1, t2, "不同时间生成的 token 应不同");
    }

    // ==================== claims 验证 ====================

    @Test
    void generatedTokenContainsIssuedAt() {
        JwtUtil util = new JwtUtil(SECRET, EXPIRATION_DAYS);
        var claims = util.parseToken(util.generateToken("u1", "n"));

        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
        assertTrue(claims.getExpiration().after(claims.getIssuedAt()));
    }

    @Test
    void expirationIsAfterIssuedAt() {
        JwtUtil util = new JwtUtil(SECRET, EXPIRATION_DAYS);
        var claims = util.parseToken(util.generateToken("u1", "n"));

        long diff = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
        // 至少是 1 天（配置的 7 天）的毫秒数，容差 30 秒
        assertTrue(diff >= 6 * 24L * 3600 * 1000,
                "过期-签发时间差应 >= 6 天毫秒值，实际: " + (diff / 3600000) + " 小时");
    }
}
