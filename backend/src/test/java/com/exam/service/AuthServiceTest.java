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

        var result = authService.register("newuser", "password123", "user");

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

    // ===== 密码强度校验 =====

    @Test
    void registerPasswordTooShortThrows() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "Ab1!", "user"));
        assertEquals("密码长度至少8位", ex.getMessage());
    }

    @Test
    void registerPasswordOnlyLettersThrows() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "abcdefgh", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void registerPasswordOnlyTwoKindsGivesSuggestion() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        // 只有小写字母 + 数字 = 2种
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "abcd1234", "user"));
        assertEquals("建议密码包含大写字母、小写字母、数字和特殊字符中的至少3种", ex.getMessage());
    }

    @Test
    void registerWeakPasswordThrows() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        // 长度8 + 只有数字(1种) → 先命中"密码需包含字母和数字"
        var ex1 = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "12345678", "user"));
        assertEquals("密码需包含字母和数字", ex1.getMessage());

        // 3种字符类型 + 常见弱密码password → 命中弱密码
        when(userMapper.findByUsername("newuser2")).thenReturn(null);
        var ex2 = assertThrows(RuntimeException.class,
            () -> authService.register("newuser2", "Password1!", "user"));
        // Password1 是常见弱密码的大小写变体
        assertTrue(ex2.getMessage().contains("过于简单"));
    }

    @Test
    void registerStrongPasswordSucceeds() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));
        // 大写+小写+数字+特殊字符 = 4种，强度足够
        var result = authService.register("newuser", "MyStr0ng!Pass", "user");
        assertNotNull(result.get("token"));
    }

    // ===== 管理员注册流程 =====

    @Test
    void registerAdminReturnsPendingMessageNoToken() {
        when(userMapper.findByUsername("admincandidate")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("admincandidate", "AdminPass1!", "admin");

        assertNull(result.get("token"));
        assertEquals("管理员申请已提交，请等待 root 审批", result.get("message"));
    }

    @Test
    void registerNullRoleDefaultsToUser() {
        when(userMapper.findByUsername("plainuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("plainuser", "MyStr0ng!Pass", null);

        assertNotNull(result.get("token"));
        @SuppressWarnings("unchecked")
        var userMap = (java.util.Map<?, ?>) result.get("user");
        assertEquals("user", userMap.get("role"));
    }

    // ===== 登录限流：锁定机制 =====

    @Test
    void loginWhenLockedThrowsLockMessage() {
        when(taskQueue.getStatus("", "auth:lock:lockeduser")).thenReturn("locked");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("lockeduser", "anypass"));
        assertEquals("登录尝试过多，请15分钟后再试", ex.getMessage());
    }

    @Test
    void loginWrongPasswordFirstTimeShowsRemainingAttempts() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));

        when(userMapper.findByUsername("u1")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:u1")).thenReturn(null);
        // 第1次失败：之前没有attempt记录
        when(taskQueue.getStatus("", "auth:attempt:u1")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("u1", "wrong"));
        assertEquals("用户名或密码错误，还剩4次尝试机会", ex.getMessage());
        verify(taskQueue).set(eq("auth:attempt:u1"), eq("1"), eq(30L * 60));
    }

    @Test
    void loginWrongPasswordFourthTimeTriggersLock() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));

        when(userMapper.findByUsername("u2")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:u2")).thenReturn(null);
        // 已有4次失败 → 第5次应触发锁定
        when(taskQueue.getStatus("", "auth:attempt:u2")).thenReturn("4");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("u2", "wrong"));
        assertEquals("密码错误次数过多，账号已锁定15分钟", ex.getMessage());
        verify(taskQueue).set(eq("auth:lock:u2"), eq("locked"), eq(15L * 60));
        verify(taskQueue).del("auth:attempt:u2");
    }

    @Test
    void loginSuccessClearsBothAttemptAndLock() {
        User user = new User();
        user.setId("u3");
        user.setUsername("testuser");
        user.setPassword(encoder.encode("correctpass"));
        user.setRole("user");
        user.setStatus("active");

        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);

        var result = authService.login("testuser", "correctpass");

        assertNotNull(result.get("token"));
        // 必须同时清除 attempt 和 lock
        verify(taskQueue).del("auth:attempt:testuser");
        verify(taskQueue).del("auth:lock:testuser");
    }

    // ===== 待审批用户登录 =====

    @Test
    void loginWithPendingStatusThrowsApprovalMessage() {
        User user = new User();
        user.setPassword(encoder.encode("correctpass"));
        user.setStatus("pending");

        when(userMapper.findByUsername("pendingadmin")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:pendingadmin")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("pendingadmin", "correctpass"));
        assertEquals("你的管理员申请正在审批中，请等待 root 审核", ex.getMessage());
    }

    // ===== me() 方法 =====

    @Test
    void meReturnsUserInfo() {
        User user = new User();
        user.setId("u-100");
        user.setUsername("meuser");
        user.setRole("user");
        user.setCreatedAt(java.time.LocalDateTime.now());

        when(userMapper.findById("u-100")).thenReturn(user);

        var result = authService.me("u-100");
        assertEquals("u-100", result.get("id"));
        assertEquals("meuser", result.get("username"));
        assertEquals("user", result.get("role"));
        assertNotNull(result.get("createdAt"));
    }

    @Test
    void meWithNonExistentUserThrows() {
        when(userMapper.findById("nouser")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.me("nouser"));
        assertEquals("用户不存在", ex.getMessage());
    }
}
