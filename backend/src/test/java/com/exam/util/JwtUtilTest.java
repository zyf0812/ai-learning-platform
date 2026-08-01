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
    void tokenContainsUsernameClaim() {
        String token = jwtUtil.generateToken("uid-999", "johndoe");
        var claims = jwtUtil.parseToken(token);
        assertEquals("johndoe", claims.get("username", String.class));
    }

    @Test
    void tokenExpirationIsCorrectFor7Days() {
        long before = System.currentTimeMillis();
        String token = jwtUtil.generateToken("u1", "x");
        long after = System.currentTimeMillis();

        var claims = jwtUtil.parseToken(token);

        long expMillis = claims.getExpiration().getTime();
        long expectedMin = before + 7L * 24 * 3600 * 1000 - 1000;
        long expectedMax = after + 7L * 24 * 3600 * 1000 + 1000;

        assertTrue(expMillis >= expectedMin && expMillis <= expectedMax,
            "Expiration " + expMillis + " should be between " + expectedMin + " and " + expectedMax);
    }

    @Test
    void tokenIssuedAtIsWithinAcceptableRange() {
        long before = System.currentTimeMillis();
        String token = jwtUtil.generateToken("u1", "x");
        long after = System.currentTimeMillis();

        var claims = jwtUtil.parseToken(token);
        long iatMillis = claims.getIssuedAt().getTime();

        assertTrue(iatMillis >= before - 1000 && iatMillis <= after + 1000,
            "IssuedAt " + iatMillis + " should be between " + before + " and " + after);
    }

    @Test
    void tamperedSignatureThrows() {
        String valid = jwtUtil.generateToken("u1", "x");
        // 篡改token签名部分（最后一段）
        int lastDot = valid.lastIndexOf('.');
        String tampered = valid.substring(0, lastDot) + ".tamperedsigpart00000000000000";
        assertThrows(Exception.class, () -> jwtUtil.parseToken(tampered));
    }

    @Test
    void emptyTokenThrows() {
        assertThrows(Exception.class, () -> jwtUtil.parseToken(""));
    }

    @Test
    void differentExpirationConfigHasDifferentExpiry() {
        // 使用更短的过期时间构造新实例
        JwtUtil shortLived = new JwtUtil(SECRET, 1);
        String token = shortLived.generateToken("u1", "x");
        var claims = shortLived.parseToken(token);

        long oneDayMs = 1L * 24 * 3600 * 1000;
        long exp = claims.getExpiration().getTime();
        long iat = claims.getIssuedAt().getTime();

        long actualDelta = exp - iat;
        // 1天过期的差值应接近1天毫秒数
        assertTrue(Math.abs(actualDelta - oneDayMs) < 2000,
            "Expected ~1d delta but got " + actualDelta + "ms");
    }
}
