package com.exam.config;

import com.exam.util.JwtUtil;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class JwtFilterTest {

    private static final String SECRET = "test-secret-key-that-is-long-enough-for-hmac-sha-256!!";

    private final JwtUtil jwtUtil = new JwtUtil(SECRET, 7);
    private final JwtFilter filter = new JwtFilter(jwtUtil);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validBearerTokenAuthenticatesRequest() throws Exception {
        String token = jwtUtil.generateToken("user42", "alice");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> invoked.set(true);

        filter.doFilter(request, response, chain);

        assertTrue(invoked.get());
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("user42", SecurityContextHolder.getContext().getAuthentication().getName());
        assertEquals(200, response.getStatus());
    }

    @Test
    void invalidTokenReturns401AndStopsTheChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer not.a.real.token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> invoked.set(true);

        filter.doFilter(request, response, chain);

        assertFalse(invoked.get());
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("Token无效或已过期"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void requestWithoutAuthorizationHeaderPassesThrough() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> invoked.set(true);

        filter.doFilter(new MockHttpServletRequest(), response, chain);

        assertTrue(invoked.get());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(200, response.getStatus());
    }

    @Test
    void nonBearerAuthorizationHeaderPassesThroughUnauthenticated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> invoked.set(true);

        filter.doFilter(request, response, chain);

        assertTrue(invoked.get());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
