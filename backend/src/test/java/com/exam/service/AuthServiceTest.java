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
        when(userMapper.findByUsername("short")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("short", "Ab1!", "user"));
        assertEquals("密码长度至少8位", ex.getMessage());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void registerRejectsPasswordWithOnlyOneCharacterKind() {
        when(userMapper.findByUsername("weak")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("weak", "abcdefgh", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void registerRejectsTwoKindPasswordWithStrengthSuggestion() {
        when(userMapper.findByUsername("weak2")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("weak2", "abcdefg1", "user"));
        assertEquals("建议密码包含大写字母、小写字母、数字和特殊字符中的至少3种", ex.getMessage());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void registerAdminCreatesPendingAccountWithoutToken() {
        when(userMapper.findByUsername("newadmin")).thenReturn(null);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);

        var result = authService.register("newadmin", "Abcdefg1", "admin");

        verify(userMapper).insert(captor.capture());
        assertEquals("admin", captor.getValue().getRole());
        assertEquals("pending", captor.getValue().getStatus());
        assertFalse(result.containsKey("token"));
        assertTrue(((String) result.get("message")).contains("等待 root 审批"));
    }

    @Test
    void registerWithoutRoleCreatesActiveRegularUser() {
        when(userMapper.findByUsername("plain")).thenReturn(null);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);

        var result = authService.register("plain", "Abcdefg1", null);

        verify(userMapper).insert(captor.capture());
        assertEquals("user", captor.getValue().getRole());
        assertEquals("active", captor.getValue().getStatus());
        assertNotNull(result.get("token"));
    }

    @Test
    void loginRejectsLockedAccountBeforeCheckingCredentials() {
        when(taskQueue.getStatus("", "auth:lock:alice")).thenReturn("locked");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("alice", "Abcdefg1"));
        assertEquals("登录尝试过多，请15分钟后再试", ex.getMessage());
        verifyNoInteractions(userMapper);
    }

    @Test
    void fifthFailedAttemptLocksAccountForFifteenMinutes() {
        User user = new User();
        user.setPassword(encoder.encode("Correct1A"));
        when(userMapper.findByUsername("bob")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:bob")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:bob")).thenReturn("4");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("bob", "wrongpass"));
        assertEquals("密码错误次数过多，账号已锁定15分钟", ex.getMessage());
        verify(taskQueue).set("auth:lock:bob", "locked", 15 * 60);
        verify(taskQueue).del("auth:attempt:bob");
        verify(taskQueue, never()).set(eq("auth:attempt:bob"), anyString(), eq(30 * 60L));
    }

    @Test
    void failedAttemptBelowLimitIncrementsCounter() {
        User user = new User();
        user.setPassword(encoder.encode("Correct1A"));
        when(userMapper.findByUsername("carol")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:carol")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:carol")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("carol", "wrongpass"));
        assertEquals("用户名或密码错误，还剩4次尝试机会", ex.getMessage());
        verify(taskQueue).set("auth:attempt:carol", "1", 30 * 60);
    }

    @Test
    void pendingUserCannotLoginEvenWithCorrectPassword() {
        User user = new User();
        user.setId("u9");
        user.setUsername("dave");
        user.setPassword(encoder.encode("Correct1A"));
        user.setRole("admin");
        user.setStatus("pending");
        when(userMapper.findByUsername("dave")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:dave")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("dave", "Correct1A"));
        assertEquals("你的管理员申请正在审批中，请等待 root 审核", ex.getMessage());
        verify(taskQueue).del("auth:attempt:dave");
        verify(taskQueue).del("auth:lock:dave");
    }

    @Test
    void meRejectsUnknownUser() {
        when(userMapper.findById("missing")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class, () -> authService.me("missing"));
        assertEquals("用户不存在", ex.getMessage());
    }
}
