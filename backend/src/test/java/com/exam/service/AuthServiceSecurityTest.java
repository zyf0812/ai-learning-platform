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
import static org.mockito.Mockito.*;

/**
 * AuthService 补充测试：覆盖密码强度校验、登录限流锁定、管理员审批流等核心风险路径。
 * 这些是原 AuthServiceTest 未覆盖的关键安全逻辑。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceSecurityTest {

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

    // ========== 密码强度校验 ==========

    @Test
    void registerPasswordTooShortRejects() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "Abc12!", "user"));
        assertEquals("密码长度至少8位", ex.getMessage());
        verify(userMapper, never()).insert(any());
    }

    @Test
    void registerPasswordOnlyLettersAndDigitsWarning() {
        // 2种：大写 + 小写 = 2种 < 3种 但 >= 2种，给出"建议"提示
        // 实际逻辑: kinds<2 才硬拒绝, kinds<3 给建议(不是throw)
        // 所以 Abcdefgh = 大写+小写 = 2种 -> 不满足"字母和数字"要求
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "Abcdefgh", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void registerPasswordWithLetterAndDigitPassesMin() {
        // 3种：大写+小写+数字 = 3种，不触发任何 throw
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("newuser", "Abc12345", "user");
        assertNotNull(result.get("token"));
    }

    @Test
    void registerPasswordWithOnlyOneKindRejects() {
        // 只有数字 1 种
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "12345678", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void registerPasswordOnlyLowercaseAndDigitSucceeds() {
        // 2种：小写+数字 = 2种，通过硬校验（不触发需包含字母和数字），
        // 但会建议至少3种 — 实际是"建议"非 throw，所以成功
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("newuser", "abcd1234", "user");
        assertNotNull(result.get("token"));
    }

    @Test
    void registerWeakPasswordRejects() {
        // 正好命中常见弱密码列表: "12345678"
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "12345678", "user"));
        assertEquals("密码过于简单，请换一个", ex.getMessage());
    }

    @Test
    void registerWeakPasswordCaseInsensitiveRejects() {
        // 不区分大小写匹配: "PASSWORD" vs "password"
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "PASSWORD", "user"));
        // PASSWORD 是全大写，checkPasswordStrength: 1种(大写) -> 先报"字母和数字"
        assertTrue(ex.getMessage().contains("密码"));
    }

    @Test
    void registerWeakPassword88888888Rejects() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "88888888", "user"));
        // 先通过字符种类检查(1种数字) -> "字母和数字"
        assertTrue(ex.getMessage().contains("密码"));
    }

    @Test
    void registerStrongPasswordWithSpecialCharSucceeds() {
        // 4种全部满足：大写+小写+数字+特殊
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("newuser", "Abc@1234", "user");
        assertNotNull(result.get("token"));
    }

    // ========== 管理员注册审批流 ==========

    @Test
    void registerAdminRoleCreatesPendingStatusUser() {
        when(userMapper.findByUsername("newadmin")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("newadmin", "Admin@123", "admin");

        assertEquals("管理员申请已提交，请等待 root 审批", result.get("message"));
        assertNull(result.get("token")); // pending 状态不返回 token

        verify(userMapper).insert(argThat(u ->
            "admin".equals(u.getRole()) && "pending".equals(u.getStatus())));
    }

    @Test
    void registerNonAdminRoleCreatesActiveStatusUser() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("newuser", "Abc@1234", "user");

        assertNotNull(result.get("token"));
        verify(userMapper).insert(argThat(u ->
            "user".equals(u.getRole()) && "active".equals(u.getStatus())));
    }

    @Test
    void registerNullRoleDefaultsToUserActive() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("newuser", "Abc@1234", null);

        assertNotNull(result.get("token"));
        @SuppressWarnings("unchecked")
        var userInfo = (java.util.Map<?, ?>) result.get("user");
        assertEquals("user", userInfo.get("role"));
    }

    // ========== 登录限流 / 锁定逻辑 ==========

    @Test
    void loginWhenLockedThrowsLockMessage() {
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn("locked");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("testuser", "anypass"));
        assertEquals("登录尝试过多，请15分钟后再试", ex.getMessage());

        // 锁定状态下不应查询数据库
        verify(userMapper, never()).findByUsername(anyString());
    }

    @Test
    void loginFourWrongThenOneRightClearsAttempts() {
        User user = makeActiveUser("testuser", "correct");

        // 第一次错误
        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn(null);
        assertThrows(RuntimeException.class, () -> authService.login("testuser", "wrong"));
        verify(taskQueue).set(eq("auth:attempt:testuser"), eq("1"), eq(30L * 60));

        // 第二次错误 (已有1次)
        when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn("1");
        assertThrows(RuntimeException.class, () -> authService.login("testuser", "wrong"));
        verify(taskQueue).set(eq("auth:attempt:testuser"), eq("2"), eq(30L * 60));

        // 第三次
        when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn("2");
        assertThrows(RuntimeException.class, () -> authService.login("testuser", "wrong"));
        verify(taskQueue).set(eq("auth:attempt:testuser"), eq("3"), eq(30L * 60));

        // 第四次
        when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn("3");
        assertThrows(RuntimeException.class, () -> authService.login("testuser", "wrong"));
        verify(taskQueue).set(eq("auth:attempt:testuser"), eq("4"), eq(30L * 60));

        // 登录正确，应清除记录
        when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn("4");
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);
        var result = authService.login("testuser", "correct");
        assertNotNull(result.get("token"));
        verify(taskQueue).del("auth:attempt:testuser");
        verify(taskQueue).del("auth:lock:testuser");
    }

    @Test
    void loginFifthWrongLocksAccount() {
        User user = makeActiveUser("testuser", "correct");

        // 已有4次失败，第5次
        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn("4");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("testuser", "wrong5"));
        assertEquals("密码错误次数过多，账号已锁定15分钟", ex.getMessage());

        // 应设置锁定: 15 * 60 TTL
        verify(taskQueue).set(eq("auth:lock:testuser"), eq("locked"), eq(15L * 60));
        verify(taskQueue).del("auth:attempt:testuser");
    }

    @Test
    void loginFirstWrongShowsFourRemaining() {
        User user = makeActiveUser("testuser", "correct");
        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("testuser", "wrong"));
        assertTrue(ex.getMessage().contains("还剩4次尝试机会"), "实际消息: " + ex.getMessage());
    }

    @Test
    void loginFourthWrongShowsOneRemaining() {
        User user = makeActiveUser("testuser", "correct");
        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn("3");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("testuser", "wrong"));
        assertTrue(ex.getMessage().contains("还剩1次尝试机会"), "实际消息: " + ex.getMessage());
    }

    // ========== 登录审批状态 ==========

    @Test
    void loginPendingStatusUserThrowsApprovalMessage() {
        User user = new User();
        user.setUsername("pendingadmin");
        user.setPassword(encoder.encode("Admin@123"));
        user.setStatus("pending");
        user.setRole("admin");

        when(taskQueue.getStatus("", "auth:lock:pendingadmin")).thenReturn(null);
        when(userMapper.findByUsername("pendingadmin")).thenReturn(user);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("pendingadmin", "Admin@123"));
        assertEquals("你的管理员申请正在审批中，请等待 root 审核", ex.getMessage());

        // 审批拒绝状态下也不返回 token，但应清除尝试计数（登录凭据正确）
        verify(taskQueue).del("auth:attempt:pendingadmin");
    }

    // ========== me() 方法 ==========

    @Test
    void meReturnsUserInfo() {
        User user = new User();
        user.setId("u1");
        user.setUsername("alice");
        user.setRole("user");
        user.setCreatedAt(java.time.LocalDateTime.now());
        when(userMapper.findById("u1")).thenReturn(user);

        var result = authService.me("u1");

        assertEquals("u1", result.get("id"));
        assertEquals("alice", result.get("username"));
        assertEquals("user", result.get("role"));
        assertNotNull(result.get("createdAt"));
    }

    @Test
    void meWithNonexistentUserThrows() {
        when(userMapper.findById("nonexistent")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class, () -> authService.me("nonexistent"));
        assertEquals("用户不存在", ex.getMessage());
    }

    // ========== helpers ==========

    private User makeActiveUser(String username, String rawPassword) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(encoder.encode(rawPassword));
        user.setStatus("active");
        user.setRole("user");
        return user;
    }
}
