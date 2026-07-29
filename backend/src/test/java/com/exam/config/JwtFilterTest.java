package com.exam.config;

import com.exam.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtFilterTest {

    private static final String SECRET = "test-secret-key-long-enough-for-hmac-sha256!!!";
    private static final int EXP_DAYS = 7;

    private JwtUtil jwtUtil;
    private JwtFilter filter;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET, EXP_DAYS);
        filter = new JwtFilter(jwtUtil);
    }

    // ===== 无 Authorization 头 =====

    @Test
    void noAuthHeaderProceedsWithoutSettingAuth() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getHeader("Authorization")).thenReturn(null);

        filter.doFilterInternal(req, resp, chain);

        verify(chain).doFilter(req, resp);
        // 不应写响应（正常透传）
        verify(resp, never()).getWriter();
        verify(resp, never()).setStatus(anyInt());
    }

    // ===== Authorization 头非 Bearer 前缀 =====

    @Test
    void nonBearerAuthHeaderProceedsChain() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getHeader("Authorization")).thenReturn("Basic abc123");

        filter.doFilterInternal(req, resp, chain);

        verify(chain).doFilter(req, resp);
        verify(resp, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void emptyBearerTokenProceedsChain() throws Exception {
        // "Bearer " 后面无内容 → 长度=7，substring(7) = ""
        // parseToken("") 会抛异常 → 应返回401？让我们验证实际行为
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getHeader("Authorization")).thenReturn("Bearer ");

        // 用真实StringWriter捕获输出
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        when(resp.getWriter()).thenReturn(pw);

        filter.doFilterInternal(req, resp, chain);

        // JwtUtil.parseToken("") 会抛异常 → 写401响应且不调用 chain
        verify(resp).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(req, resp);
        pw.flush();
        assertTrue(sw.toString().contains("Token无效或已过期"),
            "响应应包含Token无效消息: " + sw);
    }

    // ===== 有效 Bearer Token =====

    @Test
    void validBearerTokenSetsSecurityContextAndProceeds() throws Exception {
        String token = jwtUtil.generateToken("userid-123", "alice");

        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getHeader("Authorization")).thenReturn("Bearer " + token);

        try {
            filter.doFilterInternal(req, resp, chain);
        } finally {
            // 清理SecurityContext，避免影响其他测试
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }

        verify(chain).doFilter(req, resp);
        verify(resp, never()).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void validBearerTokenSetsCorrectPrincipal() throws Exception {
        String token = jwtUtil.generateToken("uid-999", "bob");

        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getHeader("Authorization")).thenReturn("Bearer " + token);

        try {
            filter.doFilterInternal(req, resp, chain);

            var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
            assertNotNull(auth, "SecurityContext应包含Authentication");
            // subject即userId
            assertEquals("uid-999", auth.getPrincipal());
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    // ===== 无效/伪造/过期 Token =====

    @Test
    void invalidBearerTokenReturns401AndStopsChain() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getHeader("Authorization")).thenReturn("Bearer totally.fake.token");

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        when(resp.getWriter()).thenReturn(pw);

        try {
            filter.doFilterInternal(req, resp, chain);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }

        verify(resp).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(resp).setContentType("application/json;charset=UTF-8");
        verify(chain, never()).doFilter(req, resp);
        pw.flush();
        assertTrue(sw.toString().contains("\"error\""), "响应应是JSON error");
    }

    @Test
    void malformedTokenReturns401() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        // 缺两段的JWT
        when(req.getHeader("Authorization")).thenReturn("Bearer onlyonepart");

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        when(resp.getWriter()).thenReturn(pw);

        try {
            filter.doFilterInternal(req, resp, chain);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }

        verify(resp).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(req, resp);
    }

    // ===== 不同JwtUtil实例(不同secret)签发的token应判为无效 =====

    @Test
    void tokenFromDifferentSecretReturns401() throws Exception {
        JwtUtil otherUtil = new JwtUtil("another-secret-key-different-from-main-12345", 1);
        String badToken = otherUtil.generateToken("u1", "x");

        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getHeader("Authorization")).thenReturn("Bearer " + badToken);

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        when(resp.getWriter()).thenReturn(pw);

        try {
            filter.doFilterInternal(req, resp, chain);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }

        verify(resp).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(req, resp);
    }
}
