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
import static org.mockito.Mockito.*;

/**
 * 补全 AuthService 中缺失的关键逻辑路径测试：
 * - 密码强度校验（长度、字符种类、常见弱密码）
 * - admin 注册 → pending 状态
 * - 登录限流锁定逻辑（连续失败 → 15分钟锁定 → 剩余尝试次数消息）
 * - 已锁定账号登录
 * - pending 状态用户登录被拒
 * - me() 方法
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceExtendedTest {

    @Mock private UserMapper userMapper;
    @Mock private TaskQueueService taskQueue;

    private PasswordEncoder encoder;
    private JwtUtil jwtUtil;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        encoder = new BCryptPasswordEncoder();
        jwtUtil = new JwtUtil("test-secret-key-for-hmac-sha-256-testing!!", 7);
        authService = new AuthService(userMapper, jwtUtil, encoder, taskQueue);
    }

    // ===== Password strength: 长度 =====

    @Test
    void registerPasswordTooShortThrows() {
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "Abc123", "user"));
        assertEquals("密码长度至少8位", ex.getMessage());
        verify(userMapper, never()).insert(any());
    }

    // ===== Password strength: 字符种类 < 2 (纯数字) =====

    @Test
    void registerPasswordOnlyDigitsThrows() {
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "12345678", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void registerPasswordOnlyLettersThrows() {
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "abcdefgh", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    // ===== Password strength: 种类 = 2 → 建议至少3种 (warning message) =====

    @Test
    void registerPasswordTwoKindsWarns() {
        // "Test1234" → upper + lower + digit → 3 kinds actually...
        // Need exactly 2: lowercase + digit
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "test1234", "user"));
        assertEquals("建议密码包含大写字母、小写字母、数字和特殊字符中的至少3种", ex.getMessage());
    }

    // ===== Password strength: 常见弱密码 =====

    @Test
    void registerPasswordCommonWeakThrows() {
        // "password" is length 8, but kinds=1 (all lowercase) → fails kinds check first
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "PASSWORD", "user"));
        // "PASSWORD" length 8, kinds=1 (all upper)
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void registerPasswordCommonWeakCaseInsensitive() {
        // Weak list entry "12345678" — length 8, kinds=1(digit) → fails kinds check
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "88888888", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void registerPasswordThreeKindsPassesStrength() {
        // With current weak-list entries all having <3 kinds, a 3+ kind valid password
        // (length>=8, kinds>=3, not in weak list) passes checkPasswordStrength.
        when(userMapper.findByUsername("valid")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        // "Test@2024": length 8+, kinds=upper+lower+digit+special = 4
        var result = authService.register("valid", "Test@2024", "user");
        assertNotNull(result.get("token"));
        verify(userMapper).insert(any());
    }

    // ===== Password strength: 强密码通过 =====

    @Test
    void registerStrongPasswordPasses() {
        when(userMapper.findByUsername("strong")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        // Upper + lower + digit + special → 4 kinds, length 12
        var result = authService.register("strong", "Test@2024!OK", "user");
        assertNotNull(result.get("token"));
        verify(userMapper).insert(any());
    }

    // ===== Admin registration → pending status =====

    @Test
    void registerAdminReturnsPendingMessage() {
        when(userMapper.findByUsername("boss")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("boss", "Admin@2024", "admin");

        assertEquals("管理员申请已提交，请等待 root 审批", result.get("message"));
        assertNull(result.get("token")); // pending admin not issued token
        verify(userMapper).insert(any(User.class));
    }

    @Test
    void registerAdminUserIsInsertedPending() {
        when(userMapper.findByUsername("boss")).thenReturn(null);
        var userCapture = org.mockito.ArgumentCaptor.forClass(User.class);

        authService.register("boss", "Admin@2024", "admin");

        verify(userMapper).insert(userCapture.capture());
        User saved = userCapture.getValue();
        assertEquals("admin", saved.getRole());
        assertEquals("pending", saved.getStatus());
    }

    // ===== Login: account already locked =====

    @Test
    void loginLockedAccountThrowsImmediately() {
        when(taskQueue.getStatus("", "auth:lock:lockeduser")).thenReturn("locked");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("lockeduser", "anypass"));
        assertEquals("登录尝试过多，请15分钟后再试", ex.getMessage());

        verify(userMapper, never()).findByUsername(anyString());
    }

    // ===== Login: first wrong password → 4 remaining attempts =====

    @Test
    void loginFirstWrongPasswordShowsRemainingAttempts() {
        User u = new User();
        u.setPassword(encoder.encode("correct"));
        when(userMapper.findByUsername("u1")).thenReturn(u);
        when(taskQueue.getStatus("", "auth:lock:u1")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:u1")).thenReturn(null); // 0 prior

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("u1", "wrong"));
        assertTrue(ex.getMessage().contains("还剩4次尝试机会"),
            "Expected 4 remaining, got: " + ex.getMessage());

        verify(taskQueue).set(eq("auth:attempt:u1"), eq("1"), eq(30 * 60L));
    }

    // ===== Login: 4th wrong (total 5) → lock account =====

    @Test
    void loginFifthWrongPasswordLocksAccount() {
        User u = new User();
        u.setPassword(encoder.encode("correct"));
        when(userMapper.findByUsername("u1")).thenReturn(u);
        when(taskQueue.getStatus("", "auth:lock:u1")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:u1")).thenReturn("4"); // 4 prior attempts

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("u1", "wrong"));
        assertEquals("密码错误次数过多，账号已锁定15分钟", ex.getMessage());

        verify(taskQueue).set(eq("auth:lock:u1"), eq("locked"), eq(15 * 60L));
        verify(taskQueue).del("auth:attempt:u1");
    }

    // ===== Login: wrong password increments correctly =====

    @Test
    void loginThirdWrongIncrementsAttemptCount() {
        User u = new User();
        u.setPassword(encoder.encode("correct"));
        when(userMapper.findByUsername("u1")).thenReturn(u);
        when(taskQueue.getStatus("", "auth:lock:u1")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:u1")).thenReturn("2"); // 2 prior attempts

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("u1", "wrong"));
        assertTrue(ex.getMessage().contains("还剩2次尝试机会"),
            "Expected 2 remaining: 5 - (2+1) = 2, got: " + ex.getMessage());

        verify(taskQueue).set(eq("auth:attempt:u1"), eq("3"), eq(30 * 60L));
    }

    // ===== Login: non-existent user treated as wrong password =====

    @Test
    void loginNonexistentUserCountsAsAttempt() {
        when(userMapper.findByUsername("ghost")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:lock:ghost")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:ghost")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("ghost", "something"));
        assertTrue(ex.getMessage().contains("用户名或密码错误"));

        verify(taskQueue).set(eq("auth:attempt:ghost"), eq("1"), eq(30 * 60L));
    }

    // ===== Login: pending user rejected after password check =====

    @Test
    void loginPendingUserThrowsApprovalMessage() {
        User u = new User();
        u.setId("u1");
        u.setUsername("pending");
        u.setPassword(encoder.encode("correct"));
        u.setStatus("pending");
        when(userMapper.findByUsername("pending")).thenReturn(u);
        when(taskQueue.getStatus("", "auth:lock:pending")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("pending", "correct"));
        assertEquals("你的管理员申请正在审批中，请等待 root 审核", ex.getMessage());

        // Del was called for attempt records (login success flow clears them)
        verify(taskQueue).del("auth:attempt:pending");
    }

    // ===== Login: successful login returns user map =====

    @Test
    void loginSuccessReturnsUserInfoWithRole() {
        User u = new User();
        u.setId("u1");
        u.setUsername("alice");
        u.setPassword(encoder.encode("pass"));
        u.setRole("user");
        u.setStatus("active");
        when(userMapper.findByUsername("alice")).thenReturn(u);
        when(taskQueue.getStatus("", "auth:lock:alice")).thenReturn(null);

        var result = authService.login("alice", "pass");

        assertNotNull(result.get("token"));
        @SuppressWarnings("unchecked")
        var userMap = (java.util.Map<String, Object>) result.get("user");
        assertEquals("u1", userMap.get("id"));
        assertEquals("alice", userMap.get("username"));
        assertEquals("user", userMap.get("role"));
    }

    // ===== me() method =====

    @Test
    void meExistingUserReturnsProfile() {
        User u = new User();
        u.setId("u1");
        u.setUsername("me");
        u.setRole("user");
        u.setCreatedAt(java.time.LocalDateTime.now());
        when(userMapper.findById("u1")).thenReturn(u);

        var result = authService.me("u1");
        assertEquals("u1", result.get("id"));
        assertEquals("me", result.get("username"));
        assertEquals("user", result.get("role"));
        assertNotNull(result.get("createdAt"));
    }

    @Test
    void meMissingUserThrows() {
        when(userMapper.findById("ghost")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class, () -> authService.me("ghost"));
        assertEquals("用户不存在", ex.getMessage());
    }
}
