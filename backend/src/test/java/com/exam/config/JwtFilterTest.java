package com.exam.config;

import com.exam.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;

class JwtFilterTest {

    private final JwtUtil jwtUtil =
            new JwtUtil("test-secret-key-that-is-long-enough-for-hmac-sha-256!!", 7);
    private final JwtFilter filter = new JwtFilter(jwtUtil);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validBearerTokenAuthenticatesRequest() throws Exception {
        String token = jwtUtil.generateToken("user-1", "alice");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/exams");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("user-1", SecurityContextHolder.getContext().getAuthentication().getName());
        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest(), "合法请求应继续经过过滤器链");
    }

    @Test
    void invalidTokenReturns401AndStopsChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/exams");
        request.addHeader("Authorization", "Bearer invalid.token.value");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("Token无效或已过期，请重新登录"));
        assertNull(chain.getRequest(), "校验失败的请求不应继续进入业务链");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void tokenSignedWithDifferentSecretIsRejected() throws Exception {
        JwtUtil other = new JwtUtil("another-secret-key-that-is-long-enough-for-hmac!!", 7);
        String token = other.generateToken("user-1", "alice");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/exams");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void requestWithoutAuthorizationHeaderPassesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void nonBearerAuthorizationHeaderIsIgnored() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/exams");
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
