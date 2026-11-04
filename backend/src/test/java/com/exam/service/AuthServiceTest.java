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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private TaskQueueService taskQueue;

    private PasswordEncoder encoder;
    private JwtUtil jwtUtil;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        encoder = new BCryptPasswordEncoder();
        jwtUtil = new JwtUtil("test-secret-key-for-hmac-sha-256-testing!!", 7);
        authService = new AuthService(userMapper, jwtUtil, encoder, taskQueue);
    }

    @Test
    void registerNewUserReturnsToken() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("newuser", "StrongPass123!", "user");

        assertNotNull(result.get("token"));
        assertEquals("user", ((java.util.Map<?, ?>) result.get("user")).get("role"));
        verify(userMapper).insert(any(User.class));
    }

    @Test
    void registerDuplicateUsernameThrows() {
        when(userMapper.findByUsername("existing")).thenReturn(new User());

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("existing", "pass", "user"));
        assertEquals("用户名已存在", ex.getMessage());
    }

    @Test
    void loginWithValidCredentialsReturnsToken() {
        User user = new User();
        user.setId("u1");
        user.setUsername("testuser");
        user.setPassword(encoder.encode("correctpass"));
        user.setRole("user");
        user.setStatus("active");

        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);

        var result = authService.login("testuser", "correctpass");

        assertNotNull(result.get("token"));
        verify(taskQueue).del("auth:attempt:testuser");
        verify(taskQueue).del("auth:lock:testuser");
    }

    @Test
    void loginWithWrongPasswordThrows() {
        User user = new User();
        user.setPassword(encoder.encode("correctpass"));

        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn(null);

        assertThrows(RuntimeException.class,
            () -> authService.login("testuser", "wrongpass"));
    }

    @Test
    void registerAdminReturnsPendingMessageWithoutToken() {
        when(userMapper.findByUsername("root")).thenReturn(null);

        Map<String, Object> result = authService.register("root", "StrongPass123!", "admin");

        assertNull(result.get("token"));
        assertEquals("管理员申请已提交，请等待 root 审批", result.get("message"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertEquals("admin", captor.getValue().getRole());
        assertEquals("pending", captor.getValue().getStatus());
        assertTrue(encoder.matches("StrongPass123!", captor.getValue().getPassword()));
    }

    @Test
    void registerRejectsTooShortPassword() {
        when(userMapper.findByUsername("u")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "Ab1!", "user"));
        assertEquals("密码长度至少8位", ex.getMessage());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void registerRejectsPasswordWithFewerThanThreeCharacterClasses() {
        when(userMapper.findByUsername("u")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "abcdefgh1", "user"));
        assertEquals("建议密码包含大写字母、小写字母、数字和特殊字符中的至少3种", ex.getMessage());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void loginBlocksWhenAccountAlreadyLocked() {
        when(taskQueue.getStatus("", "auth:lock:u")).thenReturn("locked");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("u", "whatever"));

        assertEquals("登录尝试过多，请15分钟后再试", ex.getMessage());
        verify(userMapper, never()).findByUsername("u");
    }

    @Test
    void loginLocksAccountAfterFifthFailedAttempt() {
        User user = new User();
        user.setPassword(encoder.encode("correctpass"));

        when(userMapper.findByUsername("u")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:u")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:u")).thenReturn("4");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("u", "wrongpass"));

        assertEquals("密码错误次数过多，账号已锁定15分钟", ex.getMessage());
        verify(taskQueue).set("auth:lock:u", "locked", 15 * 60);
        verify(taskQueue).del("auth:attempt:u");
    }

    @Test
    void loginFailureReportsRemainingAttempts() {
        User user = new User();
        user.setPassword(encoder.encode("correctpass"));

        when(userMapper.findByUsername("u")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:u")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:u")).thenReturn("2");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("u", "wrongpass"));

        assertEquals("用户名或密码错误，还剩2次尝试机会", ex.getMessage());
        verify(taskQueue).set("auth:attempt:u", "3", 30 * 60);
    }

    @Test
    void loginRejectsPendingAdminApplication() {
        User user = new User();
        user.setPassword(encoder.encode("correctpass"));
        user.setStatus("pending");

        when(userMapper.findByUsername("u")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:u")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("u", "correctpass"));

        assertEquals("你的管理员申请正在审批中，请等待 root 审核", ex.getMessage());
        verify(taskQueue).del("auth:attempt:u");
        verify(taskQueue).del("auth:lock:u");
    }
}
