package com.exam.controller;

import com.exam.mapper.NotificationMapper;
import com.exam.mapper.UserMapper;
import com.exam.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 通知的发送与全局广播具有明确的角色边界，越权会影响全部用户。
 */
@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

    @Mock
    private NotificationMapper notificationMapper;
    @Mock
    private UserMapper userMapper;

    private NotificationController controller;

    @BeforeEach
    void setUp() {
        controller = new NotificationController(notificationMapper, userMapper);
    }

    private Authentication auth(String name) {
        return new UsernamePasswordAuthenticationToken(name, null, List.of());
    }

    private User user(String id, String role) {
        User u = new User();
        u.setId(id);
        u.setUsername(id);
        u.setRole(role);
        return u;
    }

    @Test
    void sendRejectsNormalUser() {
        when(userMapper.findById("u1")).thenReturn(user("u1", "user"));

        ResponseEntity<?> response = controller.send(
            Map.of("title", "标题", "content", "内容", "userIds", List.of("u2")), auth("u1"));

        assertEquals(403, response.getStatusCode().value());
        verify(notificationMapper, never()).insertDirect(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void sendCreatesOneNotificationPerRecipient() {
        when(userMapper.findById("admin1")).thenReturn(user("admin1", "admin"));

        ResponseEntity<?> response = controller.send(
            Map.of("title", "标题", "content", "内容", "userIds", List.of("u1", "u2")), auth("admin1"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2, ((Map<?, ?>) response.getBody()).get("sent"));

        ArgumentCaptor<String> toUser = ArgumentCaptor.forClass(String.class);
        verify(notificationMapper, times(2))
            .insertDirect(anyString(), eq("admin1"), toUser.capture(), eq("标题"), eq("内容"));
        assertEquals(List.of("u1", "u2"), toUser.getAllValues());
    }

    @Test
    void broadcastRejectsEvenAdmin() {
        when(userMapper.findById("admin1")).thenReturn(user("admin1", "admin"));

        ResponseEntity<?> response = controller.broadcast(Map.of("title", "标题", "content", "内容"), auth("admin1"));

        assertEquals(403, response.getStatusCode().value());
        verify(notificationMapper, never()).insertBroadcast(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void broadcastAllowsRoot() {
        when(userMapper.findById("root1")).thenReturn(user("root1", "root"));

        ResponseEntity<?> response = controller.broadcast(Map.of("title", "标题", "content", "内容"), auth("root1"));

        assertEquals(200, response.getStatusCode().value());
        verify(notificationMapper).insertBroadcast(anyString(), eq("root1"), eq("标题"), eq("内容"));
    }

    @Test
    void readMarksNotificationAsRead() {
        ResponseEntity<?> response = controller.read("n1");

        assertEquals(200, response.getStatusCode().value());
        verify(notificationMapper).markRead("n1");
    }

    @Test
    void listReturnsNotificationsAndUnreadCount() {
        when(notificationMapper.findForUser("u1")).thenReturn(List.of(Map.of("id", "n1")));
        when(notificationMapper.countUnread("u1")).thenReturn(3);

        ResponseEntity<?> response = controller.list(auth("u1"));

        assertEquals(200, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(3, body.get("unread"));
        assertEquals(1, ((List<?>) body.get("notifications")).size());
    }
}
