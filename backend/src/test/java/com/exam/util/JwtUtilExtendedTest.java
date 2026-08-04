package com.exam.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtUtil 补充测试：空token、null token、签名被篡改的token。
 * 原有 JwtUtilTest 只覆盖了正常路径和简单的无效 token。
 */
class JwtUtilExtendedTest {

    private static final String SECRET = "test-secret-key-that-is-long-enough-for-hmac-sha-256!!";
    private static final int DAYS = 7;

    private final JwtUtil jwtUtil = new JwtUtil(SECRET, DAYS);

    @Test
    void parseNullTokenThrows() {
        assertThrows(Exception.class, () -> jwtUtil.parseToken(null));
    }

    @Test
    void parseEmptyTokenThrows() {
        assertThrows(Exception.class, () -> jwtUtil.parseToken(""));
    }

    @Test
    void parseBlankTokenThrows() {
        assertThrows(Exception.class, () -> jwtUtil.parseToken("   "));
    }

    @Test
    void parseMalformedSinglePartThrows() {
        assertThrows(Exception.class, () -> jwtUtil.parseToken("abcdefg"));
    }

    @Test
    void parseMalformedTwoPartsThrows() {
        assertThrows(Exception.class, () -> jwtUtil.parseToken("a.b"));
    }

    @Test
    void parseTamperedSignatureThrows() {
        String valid = jwtUtil.generateToken("u1", "alice");
        // Replace the last 2 chars of signature (3rd part) to break it
        String[] parts = valid.split("\\.");
        assertEquals(3, parts.length);
        String sig = parts[2];
        String tamperedSig = sig.substring(0, Math.max(0, sig.length() - 2)) + "XX";
        String tampered = parts[0] + "." + parts[1] + "." + tamperedSig;

        assertThrows(Exception.class, () -> jwtUtil.parseToken(tampered));
    }

    @Test
    void parseTokenFromDifferentSecretThrows() {
        // Token generated with a completely different key should fail to parse
        JwtUtil other = new JwtUtil("different-secret-key-must-be-long-enough-xx!!", 1);
        String foreignToken = other.generateToken("u1", "alice");

        assertThrows(Exception.class, () -> jwtUtil.parseToken(foreignToken));
    }

    @Test
    void parseOnlyBase64JunkThrows() {
        // Random-looking base64 parts with no valid JWT structure
        assertThrows(Exception.class, () -> jwtUtil.parseToken("AAAA.BBBB.CCCC"));
    }

    @Test
    void tokenContainsUsernameClaim() {
        String token = jwtUtil.generateToken("id-001", "bob");
        var claims = jwtUtil.parseToken(token);
        assertEquals("bob", claims.get("username", String.class));
    }

    @Test
    void tokenIssuedAtIsSet() {
        long before = System.currentTimeMillis();
        String token = jwtUtil.generateToken("u", "name");
        long after = System.currentTimeMillis();

        var claims = jwtUtil.parseToken(token);
        long iat = claims.getIssuedAt().getTime();
        assertTrue(iat >= before - 1000 && iat <= after + 1000,
            "issuedAt should be within creation time window");
    }

    @Test
    void tokenExpirationMatchesDays() {
        long before = System.currentTimeMillis();
        String token = jwtUtil.generateToken("u", "name");
        long after = System.currentTimeMillis();

        var claims = jwtUtil.parseToken(token);
        long exp = claims.getExpiration().getTime();
        long expectedMin = before + DAYS * 24L * 3600 * 1000 - 1000;
        long expectedMax = after + DAYS * 24L * 3600 * 1000 + 1000;
        assertTrue(exp >= expectedMin && exp <= expectedMax,
            "expiration should be ~" + DAYS + " days from now");
    }
}
