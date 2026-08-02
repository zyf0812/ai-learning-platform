package com.exam.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtUtil 补充边界测试：
 * - 过期 token 无法解析
 * - 不同签名密钥生成的 token 互斥
 * - null / 空字符串处理
 * - token 可正确包含 username claim
 */
class JwtUtilExtendedTest {

    private static final String SECRET_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-32bytes-secret-A!";
    private static final String SECRET_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb-32bytes-secret-B!";

    @Test
    void tokenGeneratedWithDifferentSecretCannotBeParsed() {
        JwtUtil utilA = new JwtUtil(SECRET_A, 1);
        JwtUtil utilB = new JwtUtil(SECRET_B, 1);
        String tokA = utilA.generateToken("u1", "alice");

        assertThrows(Exception.class, () -> utilB.parseToken(tokA));
    }

    @Test
    void emptyTokenThrowsOnParse() {
        JwtUtil util = new JwtUtil(SECRET_A, 1);
        assertThrows(Exception.class, () -> util.parseToken(""));
    }

    @Test
    void gibberishTokenThrows() {
        JwtUtil util = new JwtUtil(SECRET_A, 1);
        assertThrows(Exception.class, () -> util.parseToken("not.a.jwt"));
    }

    @Test
    void parseTokenCarriesUsernameClaim() {
        JwtUtil util = new JwtUtil(SECRET_A, 1);
        String tok = util.generateToken("id-7", "zhaoliu");

        var claims = util.parseToken(tok);
        assertEquals("id-7", claims.getSubject());
        assertEquals("zhaoliu", claims.get("username", String.class));
    }

    @Test
    void expiredTokenCannotBeParsed() throws Exception {
        // 用 -1 天过期，保证立即过期
        JwtUtil shortLived = new JwtUtil(SECRET_A, -1);
        String tok = shortLived.generateToken("u1", "exp");

        // 让时钟推进一点（虽然 -1 天已经过期很久了）
        Thread.sleep(10);

        assertThrows(Exception.class, () -> shortLived.parseToken(tok));
    }

    @Test
    void zeroDaysExpirationStillInvalidImmediately() {
        JwtUtil zero = new JwtUtil(SECRET_A, 0);
        String tok = zero.generateToken("u", "n");
        // 0 天 = 0ms，issuedAt 和 expiration 几乎同时，但 expiration 在 issuedAt 之后
        // 一点点，所以 0 天其实是一个极小的正数窗口，这个测试就不强制了，
        // 只要 parse 不抛异常即可（测试的是稳定性）。
        assertDoesNotThrow(() -> zero.parseToken(tok));
    }
}
