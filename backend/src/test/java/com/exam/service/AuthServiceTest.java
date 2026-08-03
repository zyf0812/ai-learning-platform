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

    // ==================== register 基础测试 ====================

    @Test
    void registerNewUserReturnsToken() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("newuser", "password123", "user");

        assertNotNull(result.get("token"));
        assertEquals("user", ((java.util.Map<?, ?>) result.get("user")).get("role"));
        verify(userMapper).insert(any(User.class));
    }

    @Test
    void registerDuplicateUsernameThrows() {
        when(userMapper.findByUsername("existing")).thenReturn(new User());

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("existing", "Pass1234!", "user"));
        assertEquals("用户名已存在", ex.getMessage());
    }

    @Test
    void registerAdminReturnsPendingMessage() {
        when(userMapper.findByUsername("newadmin")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("newadmin", "AdminPass123!", "admin");

        assertEquals("管理员申请已提交，请等待 root 审批", result.get("message"));
        assertNull(result.get("token"));
        verify(userMapper).insert(argThat(u ->
            "admin".equals(u.getRole()) && "pending".equals(u.getStatus())));
    }

    // ==================== 密码强度校验测试 ====================

    @Test
    void registerPasswordTooShortThrows() {
        when(userMapper.findByUsername("u1")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "Ab1!", "user"));
        assertEquals("密码长度至少8位", ex.getMessage());
    }

    @Test
    void registerPasswordOnlyDigitsThrows() {
        when(userMapper.findByUsername("u1")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "1234567890", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void registerPasswordOnlyLettersThrows() {
        when(userMapper.findByUsername("u1")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "abcdefgh", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void registerPasswordOnlyTwoKindsGivesWarning() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        // 小写字母 + 数字 = 2种，应提示建议升级
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "abcd1234", "user"));
        assertTrue(ex.getMessage().contains("建议密码包含"),
            "Expected weak password suggestion, got: " + ex.getMessage());
    }

    @Test
    void registerWeakCommonPasswordThrows() {
        when(userMapper.findByUsername("u1")).thenReturn(null);

        // 使用 Abc123456：大写 A + 小写 bc + 数字 123456 = 3 种字符类通过 kinds 检查，
        // 然后命中弱密码列表中的 "abc123456"（不区分大小写比较）
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "Abc123456", "user"));
        assertEquals("密码过于简单，请换一个", ex.getMessage());
    }

    @Test
    void registerWeakPasswordCaseInsensitiveMatch() {
        when(userMapper.findByUsername("u1")).thenReturn(null);

        // Qwerty123: 大写 Q + 小写 werty + 数字 123 = 3 种字符类通过 kinds，
        // 然后与弱密码列表中 "qwerty123" 不区分大小写匹配
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "Qwerty123", "user"));
        assertEquals("密码过于简单，请换一个", ex.getMessage());
    }

    @Test
    void registerStrongPasswordWithAllFourKindsSucceeds() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        // 大写+小写+数字+特殊字符 = 4种，应通过
        var result = authService.register("u1", "Str0ng!Pass", "user");
        assertNotNull(result.get("token"));
    }

    // ==================== login 登录限流测试 ====================

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
    void loginAlreadyLockedThrows() {
        when(taskQueue.getStatus("", "auth:lock:lockeduser")).thenReturn("locked");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("lockeduser", "anypass"));
        assertEquals("登录尝试过多，请15分钟后再试", ex.getMessage());
        verify(userMapper, never()).findByUsername(anyString());
    }

    @Test
    void loginFirstWrongPasswordIncrementsAttempt() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));

        when(userMapper.findByUsername("u1")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:u1")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:u1")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("u1", "wrong"));
        assertTrue(ex.getMessage().contains("还剩4次尝试机会"),
            "Expected remaining=4 message, got: " + ex.getMessage());
        verify(taskQueue).set(eq("auth:attempt:u1"), eq("1"), eq(30L * 60));
    }

    @Test
    void loginFourthWrongPasswordIncrementsToFour() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));

        when(userMapper.findByUsername("u1")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:u1")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:u1")).thenReturn("3");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("u1", "wrong"));
        assertTrue(ex.getMessage().contains("还剩1次尝试机会"),
            "Expected remaining=1 message, got: " + ex.getMessage());
        verify(taskQueue).set(eq("auth:attempt:u1"), eq("4"), eq(30L * 60));
    }

    @Test
    void loginFifthWrongPasswordLocksAccount() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));

        when(userMapper.findByUsername("u1")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:u1")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:u1")).thenReturn("4");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("u1", "wrong"));
        assertEquals("密码错误次数过多，账号已锁定15分钟", ex.getMessage());
        verify(taskQueue).set(eq("auth:lock:u1"), eq("locked"), eq(15L * 60));
        verify(taskQueue).del("auth:attempt:u1");
    }

    @Test
    void loginSuccessClearsLockAndAttempts() {
        User user = new User();
        user.setId("u1");
        user.setPassword(encoder.encode("correct"));
        user.setStatus("active");

        when(userMapper.findByUsername("u1")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:u1")).thenReturn(null);

        authService.login("u1", "correct");

        verify(taskQueue).del("auth:attempt:u1");
        verify(taskQueue).del("auth:lock:u1");
    }

    // ==================== login pending 用户测试 ====================

    @Test
    void loginPendingAdminThrowsApprovalMessage() {
        User user = new User();
        user.setId("u1");
        user.setPassword(encoder.encode("correctpass"));
        user.setStatus("pending");
        user.setRole("admin");

        when(userMapper.findByUsername("pendingadmin")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:pendingadmin")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("pendingadmin", "correctpass"));
        assertEquals("你的管理员申请正在审批中，请等待 root 审核", ex.getMessage());
    }

    // ==================== me 方法测试 ====================

    @Test
    void meReturnsUserInfo() {
        User user = new User();
        user.setId("u123");
        user.setUsername("alice");
        user.setRole("user");
        user.setCreatedAt(java.time.LocalDateTime.now());

        when(userMapper.findById("u123")).thenReturn(user);

        var result = authService.me("u123");

        assertEquals("u123", result.get("id"));
        assertEquals("alice", result.get("username"));
        assertEquals("user", result.get("role"));
        assertNotNull(result.get("createdAt"));
    }

    @Test
    void meNonexistentUserThrows() {
        when(userMapper.findById("nouser")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.me("nouser"));
        assertEquals("用户不存在", ex.getMessage());
    }
}
