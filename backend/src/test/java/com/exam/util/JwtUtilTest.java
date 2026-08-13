package com.exam.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    private static final String SECRET = "test-secret-key-that-is-long-enough-for-hmac-sha-256!!";
    private static final int EXPIRATION_DAYS = 7;

    private final JwtUtil jwtUtil = new JwtUtil(SECRET, EXPIRATION_DAYS);

    @Test
    void generateAndParseToken() {
        String token = jwtUtil.generateToken("user123", "testuser");
        assertNotNull(token);

        var claims = jwtUtil.parseToken(token);
        assertEquals("user123", claims.getSubject());
        assertEquals("testuser", claims.get("username"));
    }

    @Test
    void tokenContainsUserIdAsSubject() {
        String token = jwtUtil.generateToken("abc123", "anyuser");
        var claims = jwtUtil.parseToken(token);
        assertEquals("abc123", claims.getSubject());
    }

    @Test
    void differentUsersHaveDifferentTokens() {
        String token1 = jwtUtil.generateToken("user1", "alice");
        String token2 = jwtUtil.generateToken("user2", "bob");
        assertNotEquals(token1, token2);
    }

    @Test
    void invalidTokenThrowsException() {
        assertThrows(Exception.class, () -> jwtUtil.parseToken("invalid.token.here"));
    }

    @Test
    void expiredTokenRejected() throws InterruptedException {
        // 使用 0 天过期时间 → 立即过期
        JwtUtil shortLived = new JwtUtil(SECRET, 0);
        String token = shortLived.generateToken("u1", "user");

        // 立即解析就应已过期（expirationMs = 0, expiration = issuedAt + 0）
        assertThrows(Exception.class, () -> shortLived.parseToken(token));
    }

    @Test
    void tokenTamperedSignatureRejected() {
        String token = jwtUtil.generateToken("u1", "user");
        // 改动末尾字符（签名部分）
        String tampered = token.substring(0, token.length() - 4) + "XXXX";
        assertThrows(Exception.class, () -> jwtUtil.parseToken(tampered));
    }

    @Test
    void emptyStringTokenRejected() {
        assertThrows(Exception.class, () -> jwtUtil.parseToken(""));
    }

    @Test
    void nullTokenRejected() {
        assertThrows(Exception.class, () -> jwtUtil.parseToken(null));
    }

    @Test
    void blankTokenRejected() {
        assertThrows(Exception.class, () -> jwtUtil.parseToken("   "));
    }

    @Test
    void tokenHasIssuedAtInPast() {
        long before = System.currentTimeMillis();
        String token = jwtUtil.generateToken("u1", "user");
        long after = System.currentTimeMillis();

        var claims = jwtUtil.parseToken(token);
        long iat = claims.getIssuedAt().getTime();

        assertTrue(iat >= before - 1000); // 允许 1 秒时钟偏差
        assertTrue(iat <= after + 1000);
    }

    @Test
    void tokenHasCorrectExpirationWindow() {
        int days = 7;
        JwtUtil util = new JwtUtil(SECRET, days);
        String token = util.generateToken("u1", "user");
        long now = System.currentTimeMillis();

        var claims = util.parseToken(token);
        long exp = claims.getExpiration().getTime();
        long expectedMin = now + (days - 1) * 24L * 3600 * 1000;
        long expectedMax = now + (days + 1) * 24L * 3600 * 1000;

        assertTrue(exp > expectedMin, "Expiration too early");
        assertTrue(exp < expectedMax, "Expiration too late");
    }
}
