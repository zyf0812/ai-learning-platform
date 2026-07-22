package com.exam.config;

import com.exam.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;

class JwtFilterTest {

    private final JwtUtil jwtUtil = new JwtUtil("test-secret-key-for-hmac-sha-256-testing!!", 7);
    private final JwtFilter filter = new JwtFilter(jwtUtil);

    @Test
    void filterSetsAuthenticationWithValidToken() throws Exception {
        String token = jwtUtil.generateToken("user123", "testuser");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("user123", SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        assertEquals(200, response.getStatus());

        SecurityContextHolder.clearContext();
    }

    @Test
    void filterPassesWithoutToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(200, response.getStatus());
    }

    @Test
    void filterRejectsInvalidToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setHeader("Authorization", "Bearer invalid.token.here");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("Token无效或已过期"));
    }

    @Test
    void filterRejectsMalformedAuthorizationHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setHeader("Authorization", "NotBearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(200, response.getStatus());
    }

    @Test
    void filterRejectsEmptyAuthorizationHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setHeader("Authorization", "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(200, response.getStatus());
    }

    @Test
    void filterRejectsBearerWithoutToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setHeader("Authorization", "Bearer ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(401, response.getStatus());
    }
}