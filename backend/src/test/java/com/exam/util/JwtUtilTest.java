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
    void tokenExpirationWorks() {
        JwtUtil shortLived = new JwtUtil(SECRET, 0);
        String token = shortLived.generateToken("user1", "test");
        assertThrows(Exception.class, () -> shortLived.parseToken(token));
    }

    @Test
    void emptyUserIdGeneratesToken() {
        String token = jwtUtil.generateToken("", "testuser");
        assertNotNull(token);
        var claims = jwtUtil.parseToken(token);
        assertEquals("", claims.getSubject());
    }

    @Test
    void emptyUsernameGeneratesToken() {
        String token = jwtUtil.generateToken("user123", "");
        assertNotNull(token);
        var claims = jwtUtil.parseToken(token);
        assertEquals("", claims.get("username"));
    }

    @Test
    void differentSecretsProduceDifferentTokens() {
        JwtUtil jwtUtil2 = new JwtUtil("different-secret-key-that-is-long-enough-for-hmac-sha-256!!", 7);
        String token1 = jwtUtil.generateToken("user1", "alice");
        String token2 = jwtUtil2.generateToken("user1", "alice");
        assertNotEquals(token1, token2);
    }

    @Test
    void cannotParseTokenWithDifferentSecret() {
        JwtUtil jwtUtil2 = new JwtUtil("different-secret-key-that-is-long-enough-for-hmac-sha-256!!", 7);
        String token = jwtUtil.generateToken("user1", "alice");
        assertThrows(Exception.class, () -> jwtUtil2.parseToken(token));
    }
}
