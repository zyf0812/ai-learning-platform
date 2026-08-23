package com.exam.util;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;

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

    // ========== 新增：JWT 结构与时间断言 ==========

    @Test
    void tokenFormatHasThreeParts() {
        String token = jwtUtil.generateToken("u1", "alice");
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length); // header.payload.signature
    }

    @Test
    void tokenHeaderDecodesToJwt() {
        String token = jwtUtil.generateToken("u1", "alice");
        String[] parts = token.split("\\.");
        // JJWT 使用无填充的 URL Base64，手动补全再解码
        String b64url = parts[0];
        int pad = (4 - b64url.length() % 4) % 4;
        String padded = b64url + "=".repeat(pad);
        String headerJson = new String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8);
        // 只校验算法字段（typ 头在 JJWT 0.12+ 中可能不包含）
        assertTrue(headerJson.contains("HS256") || headerJson.contains("HS384") || headerJson.contains("HS512"),
            "Header should declare JWS alg: " + headerJson);
    }

    @Test
    void parseReturnsValidClaimsObject() {
        String token = jwtUtil.generateToken("uid-888", "charles");
        Claims claims = jwtUtil.parseToken(token);
        assertNotNull(claims);
        assertEquals("uid-888", claims.getSubject());
        assertEquals("charles", claims.get("username", String.class));
    }

    @Test
    void issuedAtIsInRecentPast() {
        long before = System.currentTimeMillis() - 1000;
        String token = jwtUtil.generateToken("u1", "alice");
        long after = System.currentTimeMillis() + 1000;

        Date iat = jwtUtil.parseToken(token).getIssuedAt();
        assertNotNull(iat);
        assertTrue(iat.getTime() >= before);
        assertTrue(iat.getTime() <= after);
    }

    @Test
    void expirationDateIsCorrectNumberOfDaysAhead() {
        long start = System.currentTimeMillis();
        String token = jwtUtil.generateToken("u1", "alice");
        long end = System.currentTimeMillis();

        Date exp = jwtUtil.parseToken(token).getExpiration();
        assertNotNull(exp);
        long expectedMin = start + EXPIRATION_DAYS * 24L * 3600 * 1000 - 2000;
        long expectedMax = end + EXPIRATION_DAYS * 24L * 3600 * 1000 + 2000;
        assertTrue(exp.getTime() >= expectedMin, "Expiration too early");
        assertTrue(exp.getTime() <= expectedMax, "Expiration too late");
    }

    @Test
    void sameUserDifferentCallsDifferentTokens() throws InterruptedException {
        // JWT iat (NumericDate) 为秒级精度，需跨秒才能得到不同签名
        String t1 = jwtUtil.generateToken("same", "user");
        String t2 = t1;
        long deadline = System.currentTimeMillis() + 2000;
        while (t1.equals(t2) && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            t2 = jwtUtil.generateToken("same", "user");
        }
        assertNotEquals(t1, t2, "2秒内应因秒级iat差异得到不同token");
    }

    @Test
    void emptyUserIdStillGeneratesValidToken() {
        String token = jwtUtil.generateToken("", "ghost");
        assertNotNull(token);
        Claims claims = jwtUtil.parseToken(token);
        // userId 为空时，JWT subject 可能为 null 或 ""（取决于 JJWT 版本）
        assertEquals("ghost", claims.get("username"));
        assertNotNull(claims);
    }

    @Test
    void specialCharsInUsernamePreserved() {
        String token = jwtUtil.generateToken("u1", "用户_@#$%^&*()");
        Claims claims = jwtUtil.parseToken(token);
        assertEquals("用户_@#$%^&*()", claims.get("username"));
    }

    @Test
    void tamperedSignatureThrowsException() {
        String token = jwtUtil.generateToken("u1", "alice");
        // 篡改最后一位字符
        char[] chars = token.toCharArray();
        chars[chars.length - 1] = (chars[chars.length - 1] == 'A') ? 'B' : 'A';
        String tampered = new String(chars);

        assertThrows(Exception.class, () -> jwtUtil.parseToken(tampered));
    }

    @Test
    void emptyStringTokenThrows() {
        assertThrows(Exception.class, () -> jwtUtil.parseToken(""));
    }

    @Test
    void nullTokenThrows() {
        assertThrows(Exception.class, () -> jwtUtil.parseToken(null));
    }
}
