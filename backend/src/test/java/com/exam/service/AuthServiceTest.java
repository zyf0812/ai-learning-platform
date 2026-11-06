package com.exam.service;

import com.exam.mapper.UserMapper;
import com.exam.model.User;
import com.exam.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

        var result = authService.register("newuser", "Password123", "user");

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
    void registerRejectsPasswordShorterThanEightCharacters() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "Ab1", "user"));
        assertEquals("密码长度至少8位", ex.getMessage());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void registerRejectsPasswordWithOnlyOneCharacterKind() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "abcdefgh", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void registerRejectsPasswordWithOnlyTwoCharacterKinds() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "abcdef12", "user"));
        assertEquals("建议密码包含大写字母、小写字母、数字和特殊字符中的至少3种", ex.getMessage());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void registerAcceptsPasswordWithThreeCharacterKinds() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        var result = authService.register("newuser", "Abc12345", "user");

        assertNotNull(result.get("token"));
        verify(userMapper).insert(any(User.class));
    }

    @Test
    void registerAdminCreatesPendingAccountWithoutToken() {
        when(userMapper.findByUsername("adminuser")).thenReturn(null);

        var result = authService.register("adminuser", "Abc12345", "admin");

        assertNull(result.get("token"));
        assertEquals("管理员申请已提交，请等待 root 审批", result.get("message"));
        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertEquals("admin", captor.getValue().getRole());
        assertEquals("pending", captor.getValue().getStatus());
    }

    @Test
    void lockedAccountRejectsLoginBeforeCheckingPassword() {
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn("locked");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("testuser", "correctpass"));
        assertEquals("登录尝试过多，请15分钟后再试", ex.getMessage());
        verify(userMapper, never()).findByUsername(anyString());
    }

    @Test
    void fifthFailedAttemptLocksAccountForFifteenMinutes() {
        User user = new User();
        user.setUsername("testuser");
        user.setPassword(encoder.encode("correctpass"));

        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);
        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn("4");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("testuser", "wrongpass"));

        assertEquals("密码错误次数过多，账号已锁定15分钟", ex.getMessage());
        verify(taskQueue).set("auth:lock:testuser", "locked", 900L);
        verify(taskQueue).del("auth:attempt:testuser");
        verify(taskQueue, never()).set(eq("auth:attempt:testuser"), anyString(), anyLong());
    }
}
