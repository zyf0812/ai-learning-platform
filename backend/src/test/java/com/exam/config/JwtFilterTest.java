package com.exam.config;

import com.exam.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtFilter 是全局权限校验入口，此前无任何覆盖。
 * 这里锁定合法 token 认证、非法 token 立即 401、以及无/非 Bearer 头放行的行为。
 */
class JwtFilterTest {

    private static final String SECRET = "test-secret-key-that-is-long-enough-for-hmac-sha-256!!";

    private final JwtUtil jwtUtil = new JwtUtil(SECRET, 7);
    private final JwtFilter jwtFilter = new JwtFilter(jwtUtil);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validBearerTokenSetsAuthenticationAndContinuesChain() throws Exception {
        String token = jwtUtil.generateToken("user-123", "alice");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        jwtFilter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("user-123", auth.getName());
        assertEquals(200, response.getStatus());
        assertEquals(request, chain.getRequest(), "合法 token 应继续过滤器链");
    }

    @Test
    void invalidBearerTokenReturns401AndStopsChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer invalid.token.here");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        jwtFilter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentType().contains("application/json"));
        assertTrue(response.getContentAsString().contains("Token无效或已过期"));
        assertNull(chain.getRequest(), "非法 token 不应继续过滤器链");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void missingAuthorizationHeaderContinuesChainWithoutAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        jwtFilter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertEquals(request, chain.getRequest());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void nonBearerAuthorizationHeaderContinuesChainWithoutAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        jwtFilter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertEquals(request, chain.getRequest());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
