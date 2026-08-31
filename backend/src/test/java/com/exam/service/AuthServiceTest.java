package com.exam.service;

import com.exam.mapper.UserMapper;
import com.exam.model.User;
import com.exam.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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

    // ==================== 密码强度校验 ====================

    @Test
    void registerPasswordTooShortThrows() {
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "Ab1!", "user"));
        assertEquals("密码长度至少8位", ex.getMessage());
    }

    @Test
    void registerPasswordOnlyOneKindThrows() {
        // 只有小写字母，1种
        assertThrows(RuntimeException.class,
            () -> authService.register("newu", "abcdefgh", "user"),
            "只含小写字母应失败");
    }

    @Test
    void registerPasswordTwoKindsWarning() {
        // 两种（小写+数字）：kinds=2 < 3 → 提示建议
        when(userMapper.findByUsername("u2")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u2", "abcd1234", "user"));
        assertTrue(ex.getMessage().contains("建议密码包含"));
    }

    @Test
    void registerPasswordThreeKindsPasses() {
        // 大写 + 小写 + 数字 → kinds=3 通过
        when(userMapper.findByUsername("gooduser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));
        var result = authService.register("gooduser", "Hello123", "user");
        assertNotNull(result.get("token"));
    }

    @Test
    void registerPasswordFourKindsWithSpecialPasses() {
        when(userMapper.findByUsername("secureuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));
        var result = authService.register("secureuser", "Hello@123", "user");
        assertNotNull(result.get("token"));
    }

    @Test
    void registerCommonWeakPasswordRejected() {
        // 12345678 命中弱密码表
        when(userMapper.findByUsername("w1")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("w1", "12345678", "user"));
        assertEquals("密码过于简单，请换一个", ex.getMessage());
    }

    @Test
    void registerWeakPasswordCaseInsensitiveMatch() {
        // Password 大小写不敏感匹配
        when(userMapper.findByUsername("w2")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("w2", "PASSWORD", "user"));
        assertEquals("密码过于简单，请换一个", ex.getMessage());
    }

    // ==================== 管理员注册：pending 状态 ====================

    @Test
    void registerAdminRoleSetsPendingStatusNoToken() {
        when(userMapper.findByUsername("admin_candidate")).thenReturn(null);
        var userCaptor = org.mockito.ArgumentCaptor.forClass(User.class);
        doNothing().when(userMapper).insert(userCaptor.capture());

        var result = authService.register("admin_candidate", "Admin@123", "admin");

        assertEquals("pending", userCaptor.getValue().getStatus());
        assertEquals("admin", userCaptor.getValue().getRole());
        // 不返回 token，只返回审批提示
        assertNull(result.get("token"));
        assertTrue(((String) result.get("message")).contains("审批"));
    }

    @Test
    void registerNonAdminRoleDefaultsToUserActive() {
        when(userMapper.findByUsername("normal")).thenReturn(null);
        var userCaptor = org.mockito.ArgumentCaptor.forClass(User.class);
        doNothing().when(userMapper).insert(userCaptor.capture());

        authService.register("normal", "Hello123", null);

        assertEquals("user", userCaptor.getValue().getRole());
        assertEquals("active", userCaptor.getValue().getStatus());
    }

    @Test
    void registerInvalidRoleTreatedAsUser() {
        // 任何非 "admin" 的 role 都回落到 user
        when(userMapper.findByUsername("hacker")).thenReturn(null);
        var userCaptor = org.mockito.ArgumentCaptor.forClass(User.class);
        doNothing().when(userMapper).insert(userCaptor.capture());

        authService.register("hacker", "Hello123", "superadmin");

        assertEquals("user", userCaptor.getValue().getRole());
        assertEquals("active", userCaptor.getValue().getStatus());
    }

    // ==================== 登录限流 / 锁定 ====================

    @Test
    void loginAlreadyLockedThrowsImmediately() {
        when(taskQueue.getStatus("", "auth:lock:victim")).thenReturn("locked");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("victim", "anypass"));
        assertTrue(ex.getMessage().contains("15分钟"));

        // 不会去查用户
        verify(userMapper, never()).findByUsername(anyString());
    }

    @Test
    void loginFirstFailureIncrementsAttemptShowsRemaining() {
        User user = new User();
        user.setPassword(encoder.encode("realpass"));
        when(userMapper.findByUsername("alice")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:alice")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:alice")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("alice", "wrongpass"));
        assertTrue(ex.getMessage().contains("还剩4次"),
            "第1次失败后剩余应为4次，实际: " + ex.getMessage());

        verify(taskQueue).set("auth:attempt:alice", "1", 30 * 60);
    }

    @Test
    void loginFourthFailureShowsOneRemaining() {
        User user = new User();
        user.setPassword(encoder.encode("realpass"));
        when(userMapper.findByUsername("bob")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:bob")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:bob")).thenReturn("3");  // 已有3次

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("bob", "wrong"));
        assertTrue(ex.getMessage().contains("还剩1次"),
            "第4次失败应剩1次，实际: " + ex.getMessage());

        verify(taskQueue).set("auth:attempt:bob", "4", 30 * 60);
    }

    @Test
    void loginFifthFailureTriggersLockout() {
        User user = new User();
        user.setPassword(encoder.encode("realpass"));
        when(userMapper.findByUsername("badguy")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:badguy")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:badguy")).thenReturn("4");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("badguy", "wrong5"));
        assertTrue(ex.getMessage().contains("账号已锁定15分钟"));

        // 设置 lock key TTL 15分钟
        verify(taskQueue).set("auth:lock:badguy", "locked", 15 * 60);
        // 清除 attempt key
        verify(taskQueue).del("auth:attempt:badguy");
    }

    @Test
    void loginSuccessClearsBothAttemptAndLockKeys() {
        User user = new User();
        user.setId("u1");
        user.setUsername("legit");
        user.setPassword(encoder.encode("goodpass"));
        user.setRole("user");
        user.setStatus("active");

        when(userMapper.findByUsername("legit")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:legit")).thenReturn(null);

        authService.login("legit", "goodpass");

        verify(taskQueue).del("auth:attempt:legit");
        verify(taskQueue).del("auth:lock:legit");
    }

    // ==================== 登录：pending 用户 ====================

    @Test
    void loginPendingAdminRejected() {
        User user = new User();
        user.setId("u2");
        user.setUsername("waiting_admin");
        user.setPassword(encoder.encode("Admin@123"));
        user.setRole("admin");
        user.setStatus("pending");

        when(userMapper.findByUsername("waiting_admin")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:waiting_admin")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("waiting_admin", "Admin@123"));
        assertTrue(ex.getMessage().contains("审批中") || ex.getMessage().contains("审核"));
    }

    // ==================== me() ====================

    @Test
    void meExistingUserReturnsInfo() {
        User user = new User();
        user.setId("u123");
        user.setUsername("zhangsan");
        user.setRole("user");
        user.setCreatedAt(java.time.LocalDateTime.of(2025, 1, 1, 12, 0));

        when(userMapper.findById("u123")).thenReturn(user);

        var result = authService.me("u123");
        assertEquals("u123", result.get("id"));
        assertEquals("zhangsan", result.get("username"));
        assertEquals("user", result.get("role"));
        assertNotNull(result.get("createdAt"));
    }

    @Test
    void meNonExistentUserThrows() {
        when(userMapper.findById("ghost")).thenReturn(null);
        assertThrows(RuntimeException.class, () -> authService.me("ghost"));
    }
}
