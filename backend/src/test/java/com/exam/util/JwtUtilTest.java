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

    // ==================== Token 过期时间与结构 ====================

    @Test
    void tokenClaimsContainIssuedAtAndExpiration() {
        long before = System.currentTimeMillis();
        String token = jwtUtil.generateToken("u1", "alice");
        long after = System.currentTimeMillis();

        var claims = jwtUtil.parseToken(token);

        assertNotNull(claims.getIssuedAt(), "应包含签发时间 iat");
        assertNotNull(claims.getExpiration(), "应包含过期时间 exp");

        long issuedAtMs = claims.getIssuedAt().getTime();
        long expMs = claims.getExpiration().getTime();

        assertTrue(issuedAtMs >= before - 1000 && issuedAtMs <= after + 1000,
            "iat 应在调用时间窗口内");

        // 7天 = 7 * 86400 * 1000 ms
        long expectedExpDelta = EXPIRATION_DAYS * 24L * 3600 * 1000;
        long actualDelta = expMs - issuedAtMs;
        assertEquals(expectedExpDelta, actualDelta, 2000,
            "过期时长应为 " + EXPIRATION_DAYS + " 天（毫秒）");
    }

    @Test
    void tokenUsernameClaimStoredCorrectly() {
        String token = jwtUtil.generateToken("uid", "zhangsan_中文");
        var claims = jwtUtil.parseToken(token);
        assertEquals("zhangsan_中文", claims.get("username", String.class));
    }

    @Test
    void emptyOrMalformedTokenThrows() {
        assertThrows(Exception.class, () -> jwtUtil.parseToken(""));
        assertThrows(Exception.class, () -> jwtUtil.parseToken("only.one"));
        assertThrows(Exception.class, () -> jwtUtil.parseToken(null));
    }

    @Test
    void tokenSignedWithWrongSecretFails() {
        JwtUtil utilA = new JwtUtil("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 7);
        JwtUtil utilB = new JwtUtil("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 7);
        String token = utilA.generateToken("u", "name");
        assertThrows(Exception.class, () -> utilB.parseToken(token));
    }

    @Test
    void differentExpirationDaysAffectExpiry() {
        JwtUtil shortLived = new JwtUtil(SECRET, 1);
        JwtUtil longLived = new JwtUtil(SECRET, 30);

        var shortClaims = shortLived.parseToken(shortLived.generateToken("u", "n"));
        var longClaims = longLived.parseToken(longLived.generateToken("u", "n"));

        long shortDur = shortClaims.getExpiration().getTime() - shortClaims.getIssuedAt().getTime();
        long longDur = longClaims.getExpiration().getTime() - longClaims.getIssuedAt().getTime();

        assertTrue(longDur > shortDur * 10,
            "30天有效期token应明显长于1天有效期");
    }
}
