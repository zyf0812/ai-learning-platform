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

    // ========== 密码强度校验 ==========

    @Test
    void registerShortPasswordRejected() {
        when(userMapper.findByUsername("u")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "Ab1!", "user")); // 仅 5 字符
        assertEquals("密码长度至少8位", ex.getMessage());
    }

    @Test
    void registerAllDigitsOnlyOneKindRejected() {
        when(userMapper.findByUsername("u")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "12345678", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void registerLowercaseDigitOnlySuggestsStronger() {
        // 只有 2 种（小写+数字），提示建议 3 种
        when(userMapper.findByUsername("u")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "hello123", "user"));
        assertTrue(ex.getMessage().contains("建议密码包含"));
    }

    @Test
    void registerThreeKindsPasses() {
        // 大写+小写+数字 = 3 种，且不是常见弱密码 → 通过
        when(userMapper.findByUsername("u")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("u", "Hello123", "user");

        assertNotNull(result.get("token"));
    }

    @Test
    void registerCommonWeakPasswordRejected() {
        when(userMapper.findByUsername("u")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "Password123", "user"));
        assertEquals("密码过于简单，请换一个", ex.getMessage());
    }

    @Test
    void registerCommonWeakCaseInsensitive() {
        when(userMapper.findByUsername("u")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "PASSWORD123", "user"));
        assertEquals("密码过于简单，请换一个", ex.getMessage());
    }

    @Test
    void registerWeakPassword88888888() {
        when(userMapper.findByUsername("u")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "88888888", "user"));
        assertEquals("密码过于简单，请换一个", ex.getMessage());
    }

    // ========== 管理员注册 ==========

    @Test
    void registerAdminHasPendingStatus() {
        when(userMapper.findByUsername("admin1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("admin1", "AdminPass123", "admin");

        // 管理员没有 token，只有消息
        assertNull(result.get("token"));
        assertNotNull(result.get("message"));
        assertTrue(((String) result.get("message")).contains("等待"));
    }

    @Test
    void registerInvalidRoleDefaultsToUser() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("u1", "Hello123", "superadmin");

        // 未知角色会被降级为 user
        assertEquals("user", ((java.util.Map<?, ?>) result.get("user")).get("role"));
    }

    @Test
    void registerNullRoleDefaultsToUser() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("u1", "Hello123", null);

        assertEquals("user", ((java.util.Map<?, ?>) result.get("user")).get("role"));
    }

    // ========== 登录：限流/锁定 ==========

    @Test
    void loginAlreadyLockedThrowsImmediately() {
        when(taskQueue.getStatus("", "auth:lock:lockeduser")).thenReturn("locked");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("lockeduser", "anypass"));
        assertTrue(ex.getMessage().contains("15分钟"));
    }

    @Test
    void loginFirstWrongPasswordCountsAttempt() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));

        when(userMapper.findByUsername("u")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:u")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:u")).thenReturn(null); // 0 次之前

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("u", "wrong"));
        assertTrue(ex.getMessage().contains("还剩4次"));
        verify(taskQueue).set(eq("auth:attempt:u"), eq("1"), eq(30 * 60L));
    }

    @Test
    void loginFourthWrongPasswordCountsFourRemainingNone() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));

        when(userMapper.findByUsername("u")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:u")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:u")).thenReturn("4"); // 已 4 次，现在第 5 次

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("u", "wrong"));
        assertTrue(ex.getMessage().contains("账号已锁定15分钟"));
        verify(taskQueue).set(eq("auth:lock:u"), eq("locked"), eq(15 * 60L));
        verify(taskQueue).del("auth:attempt:u");
    }

    @Test
    void loginThirdWrongPasswordRemainingShown() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));

        when(userMapper.findByUsername("u")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:u")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:u")).thenReturn("2"); // 之前 2 次，现在第 3 次

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("u", "wrong"));
        // count=3, remaining = 5-3 = 2
        assertTrue(ex.getMessage().contains("还剩2次"));
        verify(taskQueue).set(eq("auth:attempt:u"), eq("3"), eq(30 * 60L));
    }

    @Test
    void loginNonexistentUserStillCountsAttempts() {
        when(userMapper.findByUsername("ghost")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:lock:ghost")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:ghost")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("ghost", "anypass"));
        assertTrue(ex.getMessage().contains("用户名或密码错误"));
        verify(taskQueue).set(eq("auth:attempt:ghost"), eq("1"), eq(30 * 60L));
    }

    // ========== 登录：待审批用户 ==========

    @Test
    void loginPendingStatusUserBlocked() {
        User user = new User();
        user.setId("u2");
        user.setUsername("pendingadmin");
        user.setPassword(encoder.encode("Correct123"));
        user.setRole("admin");
        user.setStatus("pending");

        when(userMapper.findByUsername("pendingadmin")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:pendingadmin")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("pendingadmin", "Correct123"));
        assertTrue(ex.getMessage().contains("审批中") || ex.getMessage().contains("等待"));
    }

    @Test
    void loginSuccessClearsAttemptAndLockKeys() {
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

    // ========== me() 方法 ==========

    @Test
    void meReturnsUserInfo() {
        User user = new User();
        user.setId("u1");
        user.setUsername("alice");
        user.setRole("user");

        when(userMapper.findById("u1")).thenReturn(user);

        var result = authService.me("u1");

        assertEquals("u1", result.get("id"));
        assertEquals("alice", result.get("username"));
        assertEquals("user", result.get("role"));
    }

    @Test
    void meNotFoundUserThrows() {
        when(userMapper.findById("nope")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class, () -> authService.me("nope"));
        assertEquals("用户不存在", ex.getMessage());
    }
}
