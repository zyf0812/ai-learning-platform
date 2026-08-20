package com.exam.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilEdgeCaseTest {

    private static final String SECRET = "edge-case-test-secret-long-enough-for-hmac256!";
    private final JwtUtil jwtUtil = new JwtUtil(SECRET, 1);

    @Test
    void nullTokenThrows() {
        assertThrows(Exception.class, () -> jwtUtil.parseToken(null));
    }

    @Test
    void emptyStringTokenThrows() {
        assertThrows(Exception.class, () -> jwtUtil.parseToken(""));
    }

    @Test
    void blankTokenThrows() {
        assertThrows(Exception.class, () -> jwtUtil.parseToken("   "));
    }

    @Test
    void malformedSingleSegmentThrows() {
        assertThrows(Exception.class, () -> jwtUtil.parseToken("onlyonepart"));
    }

    @Test
    void malformedTwoSegmentsThrows() {
        assertThrows(Exception.class, () -> jwtUtil.parseToken("two.parts"));
    }

    @Test
    void parseTokenUsernameClaimPersists() {
        String token = jwtUtil.generateToken("id-7", "chinese用户名");
        var claims = jwtUtil.parseToken(token);
        assertEquals("chinese用户名", claims.get("username", String.class));
    }

    @Test
    void issuedAtIsPresent() {
        String token = jwtUtil.generateToken("u1", "n1");
        var claims = jwtUtil.parseToken(token);
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
        assertTrue(claims.getExpiration().after(claims.getIssuedAt()));
    }

    @Test
    void generateTokenDifferentFromSameInputTwiceDueToTimestamp() {
        String t1 = jwtUtil.generateToken("same", "same");
        // JWT iat has second resolution, sleep >1s ensures different tokens
        try { Thread.sleep(1100); } catch (InterruptedException ignored) {}
        String t2 = jwtUtil.generateToken("same", "same");
        assertNotEquals(t1, t2);
    }

    @Test
    void tokenSignedWithDifferentSecretFailsToParse() {
        JwtUtil jwtA = new JwtUtil("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 7);
        JwtUtil jwtB = new JwtUtil("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 7);
        String token = jwtA.generateToken("u1", "n1");
        assertThrows(Exception.class, () -> jwtB.parseToken(token));
    }
}