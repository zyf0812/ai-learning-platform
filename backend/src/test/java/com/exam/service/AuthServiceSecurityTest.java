package com.exam.service;

import com.exam.mapper.UserMapper;
import com.exam.model.User;
import com.exam.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceSecurityTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private TaskQueueService taskQueue;

    private PasswordEncoder encoder;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        encoder = new BCryptPasswordEncoder();
        authService = new AuthService(userMapper, new JwtUtil(
            "test-secret-key-for-hmac-sha-256-testing!!", 7), encoder, taskQueue);
    }

    @Test
    void registerRejectsPasswordsShorterThanEightCharacters() {
        when(userMapper.findByUsername("u1")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "Ab1!", "user"));

        assertEquals("密码长度至少8位", ex.getMessage());
        verify(userMapper, never()).insert(any());
    }

    @Test
    void registerRejectsSingleKindPassword() {
        when(userMapper.findByUsername("u1")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "abcdefgh", "user"));

        assertEquals("密码需包含字母和数字", ex.getMessage());
        verify(userMapper, never()).insert(any());
    }

    @Test
    void registerRejectsPasswordWithOnlyTwoCharacterKinds() {
        when(userMapper.findByUsername("u1")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "abcdefg1", "user"));

        assertTrue(ex.getMessage().contains("至少3种"));
        verify(userMapper, never()).insert(any());
    }

    @Test
    void registerAdminCreatesPendingAccountWithoutToken() {
        when(userMapper.findByUsername("admin1")).thenReturn(null);

        Map<String, Object> result = authService.register("admin1", "StrongPass1!", "admin");

        assertFalse(result.containsKey("token"));
        assertEquals("管理员申请已提交，请等待 root 审批", result.get("message"));
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertEquals("admin", captor.getValue().getRole());
        assertEquals("pending", captor.getValue().getStatus());
    }

    @Test
    void registerDowngradesSelfDeclaredPrivilegedRole() {
        when(userMapper.findByUsername("mallory")).thenReturn(null);

        Map<String, Object> result = authService.register("mallory", "StrongPass1!", "root");

        assertNotNull(result.get("token"));
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertEquals("user", captor.getValue().getRole());
        assertEquals("active", captor.getValue().getStatus());
    }

    @Test
    void loginRejectsLockedAccountBeforeTouchingUserStore() {
        when(taskQueue.getStatus("", "auth:lock:u1")).thenReturn("locked");

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> authService.login("u1", "whatever"));

        assertEquals("登录尝试过多，请15分钟后再试", ex.getMessage());
        verifyNoInteractions(userMapper);
    }

    @Test
    void loginLocksAccountOnFifthConsecutiveFailure() {
        when(taskQueue.getStatus("", "auth:lock:u1")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:u1")).thenReturn("4");
        when(userMapper.findByUsername("u1")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> authService.login("u1", "wrong"));

        assertEquals("密码错误次数过多，账号已锁定15分钟", ex.getMessage());
        verify(taskQueue).set("auth:lock:u1", "locked", 15 * 60);
        verify(taskQueue).del("auth:attempt:u1");
    }

    @Test
    void loginReportsRemainingAttemptsBeforeLockout() {
        when(taskQueue.getStatus("", "auth:lock:u1")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:u1")).thenReturn("2");
        when(userMapper.findByUsername("u1")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> authService.login("u1", "wrong"));

        assertEquals("用户名或密码错误，还剩2次尝试机会", ex.getMessage());
        verify(taskQueue).set("auth:attempt:u1", "3", 30 * 60);
    }

    @Test
    void loginRejectsPendingAdminEvenWithCorrectPassword() {
        User admin = user("a1", "admin1", "StrongPass1!", "admin", "pending");
        when(taskQueue.getStatus("", "auth:lock:admin1")).thenReturn(null);
        when(userMapper.findByUsername("admin1")).thenReturn(admin);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> authService.login("admin1", "StrongPass1!"));

        assertEquals("你的管理员申请正在审批中，请等待 root 审核", ex.getMessage());
    }

    @Test
    void meThrowsForUnknownUser() {
        when(userMapper.findById("missing")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> authService.me("missing"));

        assertEquals("用户不存在", ex.getMessage());
    }

    private User user(String id, String username, String rawPassword, String role, String status) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword(encoder.encode(rawPassword));
        user.setRole(role);
        user.setStatus(status);
        return user;
    }
}
