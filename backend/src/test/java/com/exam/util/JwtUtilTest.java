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

    // ============ 补充测试 ============

    @Test
    void tokenContainsUsernameClaim() {
        String token = jwtUtil.generateToken("uid", "zhangsan");
        var claims = jwtUtil.parseToken(token);
        assertEquals("zhangsan", claims.get("username", String.class));
    }

    @Test
    void tokenHasIssuedAtInRecentPast() {
        long before = System.currentTimeMillis();
        String token = jwtUtil.generateToken("u1", "name");
        long after = System.currentTimeMillis();

        var claims = jwtUtil.parseToken(token);
        long issuedAt = claims.getIssuedAt().getTime();
        assertTrue(issuedAt >= before - 1000 && issuedAt <= after + 1000,
            "issuedAt should be around generation time, got: " + issuedAt);
    }

    @Test
    void tokenHasExpirationInFuture() {
        String token = jwtUtil.generateToken("u1", "name");
        var claims = jwtUtil.parseToken(token);
        long expiration = claims.getExpiration().getTime();
        long now = System.currentTimeMillis();
        // 过期时间应该在 6-8 天之后（配置是 7 天）
        long expectedMin = now + 6L * 24 * 3600 * 1000;
        long expectedMax = now + 8L * 24 * 3600 * 1000;
        assertTrue(expiration >= expectedMin && expiration <= expectedMax,
            "expiration should be ~7 days in future, diff days=" +
                (expiration - now) / (24 * 3600 * 1000.0));
    }

    @Test
    void differentExpirationConfigsProduceDifferentExpirations() {
        JwtUtil shortLived = new JwtUtil(SECRET, 1);
        JwtUtil longLived = new JwtUtil(SECRET, 30);

        String t1 = shortLived.generateToken("u1", "n1");
        String t2 = longLived.generateToken("u1", "n1");

        long e1 = shortLived.parseToken(t1).getExpiration().getTime();
        long e2 = longLived.parseToken(t2).getExpiration().getTime();
        assertTrue(e2 > e1, "30天有效期应大于1天有效期");
    }

    @Test
    void tokensFromDifferentSecretsCannotBeParsedAcross() {
        JwtUtil utilA = new JwtUtil("secret-a-long-enough-for-hmac-sha-256!", 7);
        JwtUtil utilB = new JwtUtil("secret-b-long-enough-for-hmac-sha-256!", 7);

        String tokenA = utilA.generateToken("u1", "name");
        assertThrows(Exception.class, () -> utilB.parseToken(tokenA));
    }

    @Test
    void parseEmptyStringThrows() {
        assertThrows(Exception.class, () -> jwtUtil.parseToken(""));
    }

    @Test
    void parseMalformedJwtStructureThrows() {
        // 只有两段，缺少签名段
        assertThrows(Exception.class, () -> jwtUtil.parseToken("header.payload"));
    }

    @Test
    void parseTamperedSignatureThrows() {
        String token = jwtUtil.generateToken("u1", "name");
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length);
        // 篡改签名部分
        String tampered = parts[0] + "." + parts[1] + ".AAAAAA";
        assertThrows(Exception.class, () -> jwtUtil.parseToken(tampered));
    }

    // 注意：已删除 "相同用户生成不同 token" 的测试，
    // 因为 JWT 的 issuedAt 精度是秒级，在同一秒内连续调用可能产生相同 token，
    // 该测试是时间依赖的不稳定用例，不适合回归测试。
    // 时间戳正确性已由 tokenHasIssuedAtInRecentPast 和 tokenHasExpirationInFuture 覆盖。
}
