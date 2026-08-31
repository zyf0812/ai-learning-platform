package com.exam.config;

import com.exam.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
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

@ExtendWith(MockitoExtension.class)
class JwtFilterTest {

    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain chain;

    private JwtFilter filter;
    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil("test-secret-key-for-jwt-filter-testing!!!", 7);
        filter = new JwtFilter(jwtUtil);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ==================== 无 Authorization header → 放行 ====================

    @Test
    void noAuthHeaderPassesThrough() throws ServletException, java.io.IOException {
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    // ==================== 非 Bearer 前缀 → 放行但不设置 auth ====================

    @Test
    void nonBearerHeaderPassesThroughWithoutAuth() throws ServletException, java.io.IOException {
        when(request.getHeader("Authorization")).thenReturn("Basic abcdef");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    // ==================== 有效 Bearer Token ====================

    @Test
    void validBearerTokenSetsAuthentication() throws ServletException, java.io.IOException {
        String token = jwtUtil.generateToken("userId999", "alice");
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("userId999", auth.getPrincipal());
        assertTrue(auth.getAuthorities().isEmpty());
    }

    // ==================== 无效 Token → 401 短返回 ====================

    @Test
    void invalidBearerTokenReturns401Json() throws ServletException, java.io.IOException {
        when(request.getHeader("Authorization")).thenReturn("Bearer garbage.token.value");
        StringWriter sw = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        filter.doFilterInternal(request, response, chain);

        // 不继续走 filter chain
        verify(chain, never()).doFilter(any(), any());
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(response).setContentType("application/json;charset=UTF-8");
        String body = sw.toString();
        assertTrue(body.contains("\"error\""));
        assertTrue(body.contains("Token无效或已过期"));
    }

    @Test
    void expiredOrTamperedTokenReturns401() throws ServletException, java.io.IOException {
        String valid = jwtUtil.generateToken("u1", "bob");
        String tampered = valid + "xxx";  // 篡改签名
        when(request.getHeader("Authorization")).thenReturn("Bearer " + tampered);
        StringWriter sw = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        assertFalse(SecurityContextHolder.getContext().getAuthentication() != null
            && "u1".equals(SecurityContextHolder.getContext().getAuthentication().getPrincipal()));
    }
}
