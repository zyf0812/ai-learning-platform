package com.exam.config;

import com.exam.util.JwtUtil;
import io.jsonwebtoken.impl.DefaultClaims;
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
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * JwtFilter 过滤器测试：
 * - 无 Bearer header -> 放行
 * - 有效 Bearer token -> 设置 authentication
 * - token 解析失败 -> 返回 401 + JSON 错误
 * - token 过期 -> 返回 401
 * - 前缀为 Bearer 但 token 为空 -> 解析失败流程
 */
@ExtendWith(MockitoExtension.class)
class JwtFilterTest {

    @Mock private JwtUtil jwtUtil;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain filterChain;

    private JwtFilter jwtFilter;

    @BeforeEach
    void setUp() {
        jwtFilter = new JwtFilter(jwtUtil);
        SecurityContextHolder.clearContext();
    }

    @Test
    void noHeaderPassesThrough() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);

        jwtFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void nonBearerHeaderPassesThrough() throws Exception {
        // Basic xxx 之类的其他格式不处理
        when(request.getHeader("Authorization")).thenReturn("Basic abc123");

        jwtFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void validBearerTokenSetsAuthentication() throws Exception {
        String token = "eyJhbGciOiJIUzI1NiJ9.valid-part.here";
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);

        DefaultClaims claims = new DefaultClaims(new HashMap<>());
        claims.setSubject("user-42");
        claims.put("username", "arthur");
        when(jwtUtil.parseToken(token)).thenReturn(claims);

        jwtFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("user-42", auth.getPrincipal());
        assertTrue(auth.getAuthorities().isEmpty());
    }

    @Test
    void malformedTokenReturns401Json() throws Exception {
        String bad = "Bearer not-a-real.token";
        when(request.getHeader("Authorization")).thenReturn(bad);
        when(jwtUtil.parseToken("not-a-real.token"))
            .thenThrow(new io.jsonwebtoken.MalformedJwtException("bad"));

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        when(response.getWriter()).thenReturn(pw);

        jwtFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(response).setContentType("application/json;charset=UTF-8");
        pw.flush();
        assertTrue(sw.toString().contains("Token无效或已过期"));
    }

    @Test
    void expiredTokenReturns401() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer expired-token");
        when(jwtUtil.parseToken("expired-token"))
            .thenThrow(new io.jsonwebtoken.ExpiredJwtException(null, null, "expired"));

        StringWriter sw = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        jwtFilter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void blankBearerPrefixTokenTriggersParseException() throws Exception {
        // "Bearer " 后面无内容
        when(request.getHeader("Authorization")).thenReturn("Bearer ");
        when(jwtUtil.parseToken("")).thenThrow(new RuntimeException("empty token"));

        StringWriter sw = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));

        jwtFilter.doFilterInternal(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
