package com.exam.config;

import com.exam.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;

class JwtFilterTest {

    private static final String SECRET = "test-secret-key-that-is-long-enough-for-hmac-sha-256!!";

    private JwtUtil jwtUtil;
    private JwtFilter filter;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET, 7);
        filter = new JwtFilter(jwtUtil);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validTokenAuthenticatesRequest() throws Exception {
        String token = jwtUtil.generateToken("user1", "alice");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/exams");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("user1", SecurityContextHolder.getContext().getAuthentication().getName());
        assertNotNull(chain.getRequest(), "合法令牌应继续执行过滤器链");
        assertEquals(200, response.getStatus());
    }

    @Test
    void invalidTokenReturns401AndStopsChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/exams");
        request.addHeader("Authorization", "Bearer not.a.valid.token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("Token无效或已过期"));
        assertNull(chain.getRequest(), "非法令牌不应继续执行过滤器链");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void missingAuthorizationHeaderContinuesUnauthenticated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/exams");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
    }
}
