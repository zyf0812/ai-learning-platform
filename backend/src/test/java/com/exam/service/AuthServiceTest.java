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

        // 密码必须通过强度校验：大写 + 小写 + 数字 = 3种
        var result = authService.register("newuser", "Password1", "user");

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

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("testuser", "wrongpass"));
        assertTrue(ex.getMessage().contains("用户名或密码错误"));
        assertTrue(ex.getMessage().contains("还剩")); // 剩余次数提示
    }

    // ===== 密码强度校验测试（通过 register 间接触发 checkPasswordStrength） =====

    @Test
    void registerPasswordTooShortThrows() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        // 7 位，小于 8 位要求
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "Abc1234", "user"));
        assertEquals("密码长度至少8位", ex.getMessage());
    }

    @Test
    void registerPasswordOnlyDigitsThrows() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        // 纯数字，只一种字符类型
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "12345678", "user"));
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
    void registerPasswordTwoKindsSuggestsStronger() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        // 小写字母 + 数字 = 2种 → 建议至少3种
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "abcdefg1", "user"));
        assertEquals("建议密码包含大写字母、小写字母、数字和特殊字符中的至少3种", ex.getMessage());
    }

    @Test
    void registerPasswordThreeKindsPasses() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));
        // 大写 + 小写 + 数字 = 3 种 → 通过
        var result = authService.register("newuser", "Password1", "user");
        assertNotNull(result.get("token"));
    }

    @Test
    void registerPasswordAllFourKindsPasses() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));
        // 大写 + 小写 + 数字 + 特殊 = 4 种
        var result = authService.register("newuser", "Pass@123", "user");
        assertNotNull(result.get("token"));
    }

    @Test
    void registerCommonWeakPasswordFirstFailsStrengthCheck() {
        // 注意：12345678 是纯数字（1种字符），先被「密码需包含字母和数字」拦截
        // （弱密码列表检查在字符种类检查之后，所以对全部由字母或全部由数字构成的弱密码不会触发）
        when(userMapper.findByUsername("u1")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "12345678", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void registerAllLetterWeakPasswordFirstFailsStrengthCheck() {
        // "password" / "PASSWORD" 全是字母（1种字符），先触发字符种类检查
        when(userMapper.findByUsername("u2")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u2", "PASSWORD", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void registerPasswordTwoKindsSuggestsThreeKinds() {
        // "qwerty123"（小写+数字=2种）→ 建议至少3种
        when(userMapper.findByUsername("u3")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u3", "qwerty123", "user"));
        assertTrue(ex.getMessage().contains("至少3种"));
    }

    // ===== 管理员审批流程测试 =====

    @Test
    void registerAdminRoleGetsPendingStatus() {
        when(userMapper.findByUsername("admin1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("admin1", "Admin@123", "admin");

        // 管理员申请不返回 token，而是提示等待审批
        assertNull(result.get("token"));
        assertEquals("管理员申请已提交，请等待 root 审批", result.get("message"));
    }

    @Test
    void registerAdminUserInsertedWithPendingStatus() {
        when(userMapper.findByUsername("admin2")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        authService.register("admin2", "Admin@123", "admin");

        verify(userMapper).insert(argThat(u ->
            "admin".equals(u.getRole()) && "pending".equals(u.getStatus())
        ));
    }

    @Test
    void registerNormalRoleGetsActiveStatus() {
        when(userMapper.findByUsername("norm")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        authService.register("norm", "Normal@123", "user");

        verify(userMapper).insert(argThat(u ->
            "user".equals(u.getRole()) && "active".equals(u.getStatus())
        ));
    }

    @Test
    void registerNullRoleDefaultsToUserActive() {
        when(userMapper.findByUsername("norole")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        authService.register("norole", "Normal@123", null);

        verify(userMapper).insert(argThat(u ->
            "user".equals(u.getRole()) && "active".equals(u.getStatus())
        ));
    }

    @Test
    void loginPendingAdminUserRejected() {
        User user = new User();
        user.setId("u1");
        user.setUsername("pendingAdmin");
        user.setPassword(encoder.encode("Admin@123"));
        user.setRole("admin");
        user.setStatus("pending");

        when(userMapper.findByUsername("pendingAdmin")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:pendingAdmin")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("pendingAdmin", "Admin@123"));
        assertEquals("你的管理员申请正在审批中，请等待 root 审核", ex.getMessage());
        verify(taskQueue).del("auth:attempt:pendingAdmin");
    }

    // ===== 登录限流 / 锁定机制测试 =====

    @Test
    void loginAlreadyLockedThrowsLockMessage() {
        when(taskQueue.getStatus("", "auth:lock:lockeduser")).thenReturn("locked");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("lockeduser", "anypass"));
        assertEquals("登录尝试过多，请15分钟后再试", ex.getMessage());
    }

    @Test
    void loginWrongPasswordIncrementsAttemptCount() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));

        when(userMapper.findByUsername("u1")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:u1")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:u1")).thenReturn(null); // 0 次失败

        try {
            authService.login("u1", "wrong");
        } catch (RuntimeException ignored) {}

        // 第 1 次失败，计数设为 1，TTL 30 分钟
        verify(taskQueue).set(eq("auth:attempt:u1"), eq("1"), eq(30L * 60));
    }

    @Test
    void loginWrongPasswordFourthTimeShowsRemaining() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));

        when(userMapper.findByUsername("u1")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:u1")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:u1")).thenReturn("3"); // 已失败 3 次

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("u1", "wrong"));
        assertTrue(ex.getMessage().contains("还剩1次尝试机会")); // 5 - 4 = 1
    }

    @Test
    void loginWrongPasswordFifthTimeLocksAccount() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));

        when(userMapper.findByUsername("u1")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:u1")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:u1")).thenReturn("4"); // 已失败 4 次

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("u1", "wrong"));
        assertEquals("密码错误次数过多，账号已锁定15分钟", ex.getMessage());

        // 触发锁：15 分钟 TTL
        verify(taskQueue).set(eq("auth:lock:u1"), eq("locked"), eq(15L * 60));
        // 清除 attempt 计数
        verify(taskQueue).del("auth:attempt:u1");
    }

    @Test
    void loginSuccessClearsAttemptAndLockRecords() {
        User user = new User();
        user.setId("u1");
        user.setUsername("testuser");
        user.setPassword(encoder.encode("correctpass"));
        user.setRole("user");
        user.setStatus("active");

        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);

        authService.login("testuser", "correctpass");

        verify(taskQueue).del("auth:attempt:testuser");
        verify(taskQueue).del("auth:lock:testuser");
    }

    // ===== me() 方法测试 =====

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
    void meMissingUserThrows() {
        when(userMapper.findById("nonexistent")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.me("nonexistent"));
        assertEquals("用户不存在", ex.getMessage());
    }
}
