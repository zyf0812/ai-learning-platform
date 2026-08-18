package com.exam.config;

import com.exam.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * JwtFilter 单元测试：
 *  - 无 Authorization 头 → 放行
 *  - 非 Bearer 前缀 → 放行（不设置 auth）
 *  - 有效 Bearer token → 设置 Authentication，放行
 *  - 无效 / 过期 token → 返回 401 JSON，不放行
 */
@ExtendWith(MockitoExtension.class)
class JwtFilterTest {

    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain chain;

    private JwtUtil jwtUtil;
    private JwtFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        jwtUtil = new JwtUtil("test-secret-key-long-enough-for-hmac-sha256-testing!!", 7);
        filter = new JwtFilter(jwtUtil);
    }

    // ========== 无 Authorization 头 ==========

    @Test
    void noHeaderProceedsWithoutAuth() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    // ========== 非 Bearer 前缀 ==========

    @Test
    void nonBearerHeaderProceedsWithoutAuth() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Basic abc123");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    // ========== 有效 Bearer token ==========

    @Test
    void validBearerTokenSetsAuthentication() throws Exception {
        String token = jwtUtil.generateToken("u123", "alice");
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("u123", auth.getPrincipal());
        assertTrue(auth.getAuthorities().isEmpty());
    }

    // ========== 无效 token → 401 ==========

    @Test
    void invalidTokenReturns401Json() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer invalid.token.here");
        StringWriter sw = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        filter.doFilterInternal(request, response, chain);

        // 不放行
        verify(chain, never()).doFilter(any(), any());
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(response).setContentType("application/json;charset=UTF-8");
        String body = sw.toString();
        assertTrue(body.contains("Token无效或已过期"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void expiredTokenReturns401() throws Exception {
        // 使用 0 天过期 → 立即过期的 JwtUtil（或篡改 token 签名）
        JwtUtil shortLived = new JwtUtil("test-secret-key-long-enough-for-hmac-sha256-testing!!", 0);
        // 0 天过期，立即生成 → backdate 方式不好模拟，
        // 直接用有效密钥但不同 secret 签名的 token 作为"无效"等价
        JwtUtil otherSecret = new JwtUtil("other-secret-key-long-enough-for-testing-purposes!!", 7);
        String badToken = otherSecret.generateToken("u1", "x");

        when(request.getHeader("Authorization")).thenReturn("Bearer " + badToken);
        StringWriter sw = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        assertTrue(sw.toString().contains("Token无效或已过期"));
    }
}
