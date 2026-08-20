package com.exam.config;

import com.exam.util.JwtUtil;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.FilterChain;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtFilterTest {

    private JwtUtil jwtUtil;
    private JwtFilter filter;

    @Mock
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil("filter-test-secret-key-long-enough-for-hs256!!", 7);
        filter = new JwtFilter(jwtUtil);
        SecurityContextHolder.clearContext();
    }

    @Test
    void noAuthorizationHeaderPassesThrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilterInternal(req, resp, chain);

        verify(chain).doFilter(req, resp);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(HttpServletResponse.SC_OK, resp.getStatus());
    }

    @Test
    void nonBearerAuthorizationPassesThrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Basic dXNlcjpwYXNz");

        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilterInternal(req, resp, chain);

        verify(chain).doFilter(req, resp);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void validBearerTokenSetsAuthentication() throws Exception {
        String token = jwtUtil.generateToken("user-42", "bob");

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilterInternal(req, resp, chain);

        verify(chain).doFilter(req, resp);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("user-42", auth.getPrincipal());
        assertTrue(auth.getAuthorities().isEmpty());
    }

    @Test
    void invalidBearerTokenReturns401Json() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer definitely.not.a.valid-token");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilterInternal(req, resp, chain);

        // Chain must NOT proceed
        verify(chain, never()).doFilter(any(), any());

        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, resp.getStatus());
        assertEquals("application/json;charset=UTF-8", resp.getContentType());
        String body = resp.getContentAsString();
        assertTrue(body.contains("Token无效或已过期"),
            "Expected 401 JSON error message, got: " + body);
    }

    @Test
    void tamperedBearerTokenReturns401() throws Exception {
        String token = jwtUtil.generateToken("u1", "alice");
        // Flip some characters in the signature part (after last dot)
        int lastDot = token.lastIndexOf('.');
        String tampered = token.substring(0, lastDot + 1) + "ABCDEFGHtampered";

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + tampered);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilterInternal(req, resp, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, resp.getStatus());
    }

    @Test
    void securityContextClearedBetweenRequestsWithoutAuth() throws Exception {
        // First: valid token → sets auth
        String token = jwtUtil.generateToken("u1", "alice");
        MockHttpServletRequest req1 = new MockHttpServletRequest();
        req1.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse resp1 = new MockHttpServletResponse();
        filter.doFilterInternal(req1, resp1, chain);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());

        // Then clear and simulate next request without auth header
        // (In real Spring the context is thread-bound; here we just ensure no-auth
        // requests don't rely on prior state. We'll clear to simulate a fresh thread
        // and then verify it stays null.)
        SecurityContextHolder.clearContext();

        MockHttpServletRequest req2 = new MockHttpServletRequest();
        MockHttpServletResponse resp2 = new MockHttpServletResponse();
        filter.doFilterInternal(req2, resp2, chain);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
