package com.exam.config;

import com.exam.util.JwtUtil;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * JWT 过滤器是权限校验的第一道关口：合法令牌必须建立认证上下文，
 * 非法/伪造令牌必须直接 401 且不得继续执行后续过滤器。
 */
class JwtFilterTest {

    private static final String SECRET = "test-secret-key-that-is-long-enough-for-hmac-sha-256!!";

    private final JwtUtil jwtUtil = new JwtUtil(SECRET, 7);
    private final JwtFilter filter = new JwtFilter(jwtUtil);
    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validBearerTokenPopulatesAuthenticationAndContinues() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer " + jwtUtil.generateToken("user123", "alice"));

        filter.doFilterInternal(request, response, chain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("user123", auth.getName());
        assertNull(auth.getCredentials());
        assertTrue(auth.isAuthenticated(), "合法令牌不应被标记为已认证但无凭据以外的异常状态");
        verify(chain).doFilter(request, response);
        assertEquals(200, response.getStatus());
    }

    @Test
    void malformedTokenReturns401AndStopsChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer not.a.valid.token");

        filter.doFilterInternal(request, response, chain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentType().contains("application/json"), response.getContentType());
        assertTrue(response.getContentAsString().contains("Token无效或已过期"), response.getContentAsString());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        String valid = jwtUtil.generateToken("user123", "alice");
        String tampered = valid.substring(0, valid.length() - 4) + "AAAA";

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer " + tampered);

        filter.doFilterInternal(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void missingAuthorizationHeaderPassesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(request, response);
    }

    @Test
    void nonBearerAuthorizationHeaderPassesThroughWithoutParsing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");

        filter.doFilterInternal(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(request, response);
    }
}
