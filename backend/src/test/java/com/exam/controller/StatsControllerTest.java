package com.exam.controller;

import com.exam.mapper.UserMapper;
import com.exam.model.User;
import com.exam.service.StatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 统计数据接口必须保证普通用户只能读取自己的数据，这是明确的数据越权边界。
 */
@ExtendWith(MockitoExtension.class)
class StatsControllerTest {

    @Mock
    private StatsService statsService;
    @Mock
    private UserMapper userMapper;

    private StatsController controller;

    @BeforeEach
    void setUp() {
        controller = new StatsController(statsService, userMapper);
    }

    private Authentication auth(String name) {
        return new UsernamePasswordAuthenticationToken(name, null, List.of());
    }

    @Test
    void ownStatsDoNotRequireRoleLookup() {
        when(statsService.get("u1")).thenReturn(Map.of("documentCount", 2));

        ResponseEntity<?> response = controller.get(auth("u1"), null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(Map.of("documentCount", 2), ((Map<?, ?>) response.getBody()).get("stats"));
        verify(userMapper, never()).findById(anyString());
    }

    @Test
    void otherUserStatsRejectNormalUser() {
        User user = new User();
        user.setId("u1");
        user.setRole("user");
        when(userMapper.findById("u1")).thenReturn(user);

        ResponseEntity<?> response = controller.get(auth("u1"), "u2");

        assertEquals(403, response.getStatusCode().value());
        verify(statsService, never()).get(anyString());
    }

    @Test
    void adminCanReadOtherUserStats() {
        User admin = new User();
        admin.setId("admin1");
        admin.setRole("admin");
        when(userMapper.findById("admin1")).thenReturn(admin);
        when(statsService.get("u2")).thenReturn(Map.of("documentCount", 4));

        ResponseEntity<?> response = controller.get(auth("admin1"), "u2");

        assertEquals(200, response.getStatusCode().value());
        verify(statsService).get("u2");
    }

    @Test
    void rootCanReadOtherUserStats() {
        User root = new User();
        root.setId("root1");
        root.setRole("root");
        when(userMapper.findById("root1")).thenReturn(root);
        when(statsService.get("u2")).thenReturn(Map.of("documentCount", 4));

        ResponseEntity<?> response = controller.get(auth("root1"), "u2");

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void unknownUserCannotReadOtherUserStats() {
        when(userMapper.findById("ghost")).thenReturn(null);

        ResponseEntity<?> response = controller.get(auth("ghost"), "u2");

        assertEquals(403, response.getStatusCode().value());
        verify(statsService, never()).get(anyString());
    }
}
