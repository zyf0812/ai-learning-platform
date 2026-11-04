package com.exam.controller;

import com.exam.mapper.UserMapper;
import com.exam.model.User;
import com.exam.service.StatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class StatsControllerTest {

    @Mock private StatsService statsService;
    @Mock private UserMapper userMapper;

    private MockMvc mockMvc;
    private Authentication auth;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new StatsController(statsService, userMapper)).build();
        auth = new UsernamePasswordAuthenticationToken("u1", null, List.of());
    }

    @Test
    void regularUserCannotReadOtherUsersStats() throws Exception {
        User current = new User();
        current.setId("u1");
        current.setRole("user");
        when(userMapper.findById("u1")).thenReturn(current);

        mockMvc.perform(get("/api/stats").param("userId", "u2").principal(auth))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("无权限"));

        verify(statsService, never()).get(anyString());
    }

    @Test
    void adminCanReadOtherUsersStats() throws Exception {
        User admin = new User();
        admin.setId("u1");
        admin.setRole("admin");
        when(userMapper.findById("u1")).thenReturn(admin);
        when(statsService.get("u2")).thenReturn(Map.of("examCount", 3));

        mockMvc.perform(get("/api/stats").param("userId", "u2").principal(auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stats.examCount").value(3));
    }

    @Test
    void userCanReadOwnStatsWithoutRoleLookup() throws Exception {
        when(statsService.get("u1")).thenReturn(Map.of("examCount", 1));

        mockMvc.perform(get("/api/stats").param("userId", "u1").principal(auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stats.examCount").value(1));

        verifyNoInteractions(userMapper);
    }

    @Test
    void defaultsToAuthenticatedUserWhenNoParam() throws Exception {
        when(statsService.get("u1")).thenReturn(Map.of("examCount", 1));

        mockMvc.perform(get("/api/stats").principal(auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stats.examCount").value(1));

        verifyNoInteractions(userMapper);
    }
}
