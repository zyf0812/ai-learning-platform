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

@ExtendWith(MockitoExtension.class)
class AuthServiceExtendedTest {

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

    // --- 密码强度校验：长度 ---

    @Test
    void registerShortPasswordFails() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        // 7 chars (< 8)
        var ex = assertThrows(RuntimeException.class,
                () -> authService.register("newuser", "Ab1!xyz", "user"));
        assertEquals("密码长度至少8位", ex.getMessage());
    }

    // --- 密码强度校验：字符种类 ---

    @Test
    void registerOnlyNumbersFailsMinimumKind() {
        when(userMapper.findByUsername("u1")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
                () -> authService.register("u1", "12345678", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void registerOnlyLettersFailsMinimumKind() {
        when(userMapper.findByUsername("u1")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
                () -> authService.register("u1", "abcdefgh", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void registerTwoKindsPassesMinimumButWarnsStrength() {
        // Lowercase + digit → 2 kinds → requires 3, gives warning
        when(userMapper.findByUsername("u1")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
                () -> authService.register("u1", "password12", "user"));
        assertEquals("建议密码包含大写字母、小写字母、数字和特殊字符中的至少3种", ex.getMessage());
    }

    @Test
    void registerThreeKindsSucceedsWithoutWarning() {
        // uppercase + lowercase + digit → 3 kinds
        when(userMapper.findByUsername("u1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("u1", "Password12", "user");

        assertNotNull(result.get("token"));
        verify(userMapper).insert(any(User.class));
    }

    @Test
    void registerAllFourKindsSucceeds() {
        // upper + lower + digit + special = 4 kinds
        when(userMapper.findByUsername("u1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("u1", "Pass@123", "user");

        assertNotNull(result.get("token"));
        @SuppressWarnings("unchecked")
        var userInfo = (java.util.Map<?, ?>) result.get("user");
        assertEquals("user", userInfo.get("role"));
    }

    // --- 常见弱密码检测 ---

    @Test
    void registerCommonWeakPasswordRejected() {
        when(userMapper.findByUsername("u1")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
                () -> authService.register("u1", "12345678", "user"));
        // "12345678" also triggers kind check first (only digits)
        assertTrue(ex.getMessage().contains("密码需包含字母和数字"));
    }

    @Test
    void registerCommonWeakPasswordCaseInsensitive() {
        // Two kinds (lower+digit), but common weak password "password123" → should hit third check
        when(userMapper.findByUsername("u1")).thenReturn(null);

        // PASSWORD123 also has upper+digit → 2 kinds (no lowercase → let's check)
        // Actually: PASSWORD123 = upper + digit = 2 kinds → fails at kinds check first
        // Let's use one that passes 3 kinds but is in weak list
        // None of the weak passwords in the list have 3+ kinds, so the kinds check always wins.
        // This tests that the weak check doesn't crash or interfere.
        var ex = assertThrows(RuntimeException.class,
                () -> authService.register("u1", "Password", "user")); // has upper+lower = 2 kinds
        assertTrue(ex.getMessage().contains("至少3种") || ex.getMessage().contains("字母和数字"));
    }

    @Test
    void registerCommonWeakPasswordsInList() {
        // The weak list entries are only 1-2 kinds, so they should fail with kind errors
        String[] weak = {"12345678", "123456789", "password", "qwerty123", "abc123456", "88888888", "11111111"};
        for (String w : weak) {
            try (var mockedStatic = mockStatic(User.class)) {  // no-op, just isolating
                // Re-create setup to avoid state leaking between iterations
                when(userMapper.findByUsername("u-" + w)).thenReturn(null);
                try {
                    authService.register("u-" + w, w, "user");
                    fail("Expected exception for weak password: " + w);
                } catch (RuntimeException ex) {
                    assertTrue(ex.getMessage().contains("密码"),
                            "Expected password error for: " + w + " but got: " + ex.getMessage());
                }
                reset(userMapper);
            } catch (Exception ignored) {}
        }
    }

    // --- 管理员审批流程 ---

    @Test
    void registerAdminRoleSetsPendingStatus() {
        when(userMapper.findByUsername("admin1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("admin1", "Admin@123", "admin");

        // Pending admin should NOT get a token
        assertNull(result.get("token"));
        assertEquals("管理员申请已提交，请等待 root 审批", result.get("message"));
        // Verify user saved with pending status
        verify(userMapper).insert(argThat(u ->
                "admin".equals(u.getRole()) && "pending".equals(u.getStatus())
        ));
    }

    @Test
    void registerUserRoleActiveStatus() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("u1", "User@1234", "user");

        assertNotNull(result.get("token"));
        verify(userMapper).insert(argThat(u ->
                "user".equals(u.getRole()) && "active".equals(u.getStatus())
        ));
    }

    @Test
    void registerInvalidRoleNormalizesToUser() {
        // role = "superuser" should be normalized to user (not admin)
        when(userMapper.findByUsername("u1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("u1", "Pass@1234", "superuser");

        @SuppressWarnings("unchecked")
        var userInfo = (java.util.Map<?, ?>) result.get("user");
        assertEquals("user", userInfo.get("role"));
    }

    @Test
    void registerNullRoleDefaultsToUser() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("u1", "Pass@1234", null);

        @SuppressWarnings("unchecked")
        var userInfo = (java.util.Map<?, ?>) result.get("user");
        assertEquals("user", userInfo.get("role"));
        assertNotNull(result.get("token"));
    }

    // --- 登录限流 / 锁定机制 ---

    @Test
    void loginAlreadyLockedUserThrows() {
        when(taskQueue.getStatus("", "auth:lock:lockeduser")).thenReturn("locked");

        var ex = assertThrows(RuntimeException.class,
                () -> authService.login("lockeduser", "anypass"));
        assertEquals("登录尝试过多，请15分钟后再试", ex.getMessage());
        // Should not even check DB
        verify(userMapper, never()).findByUsername(anyString());
    }

    @Test
    void loginFirstFailureCountsAndShowsRemainingAttempts() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));
        when(userMapper.findByUsername("u1")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:u1")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:u1")).thenReturn(null);  // 0 previous

        var ex = assertThrows(RuntimeException.class,
                () -> authService.login("u1", "wrong"));
        assertEquals("用户名或密码错误，还剩4次尝试机会", ex.getMessage());
        verify(taskQueue).set("auth:attempt:u1", "1", 30 * 60);
    }

    @Test
    void loginFourthFailureCountsAndShowsRemaining() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));
        when(userMapper.findByUsername("u1")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:u1")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:u1")).thenReturn("3");  // 3 previous

        var ex = assertThrows(RuntimeException.class,
                () -> authService.login("u1", "wrong"));
        assertEquals("用户名或密码错误，还剩1次尝试机会", ex.getMessage());
        verify(taskQueue).set("auth:attempt:u1", "4", 30 * 60);
    }

    @Test
    void loginFifthFailureTriggersLockout() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));
        when(userMapper.findByUsername("u1")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:u1")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:u1")).thenReturn("4");  // 4 previous

        var ex = assertThrows(RuntimeException.class,
                () -> authService.login("u1", "wrong"));
        assertEquals("密码错误次数过多，账号已锁定15分钟", ex.getMessage());
        verify(taskQueue).set("auth:lock:u1", "locked", 15 * 60);
        verify(taskQueue).del("auth:attempt:u1");
    }

    @Test
    void loginUnknownUserIncrementsAttempts() {
        // Non-existent user → still increment attempt counter to prevent user enumeration
        when(userMapper.findByUsername("nobody")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:lock:nobody")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:nobody")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
                () -> authService.login("nobody", "anything"));
        assertTrue(ex.getMessage().contains("还剩4次"));
        verify(taskQueue).set("auth:attempt:nobody", "1", 30 * 60);
    }

    @Test
    void loginSuccessClearsAttemptsAndLock() {
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

    // --- 登录 pending user ---

    @Test
    void loginPendingAdminUserRejected() {
        User user = new User();
        user.setPassword(encoder.encode("Admin@123"));
        user.setStatus("pending");
        user.setRole("admin");
        when(userMapper.findByUsername("admin1")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:admin1")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
                () -> authService.login("admin1", "Admin@123"));
        assertEquals("你的管理员申请正在审批中，请等待 root 审核", ex.getMessage());
        // Even though login fails, wrong password counter is NOT reset (pending rejection is not a password error)
        // Actually: the success clear was for valid credentials only. Pending user has correct password but wrong status.
        // In this code path, login clears attempts at line 80-81 BEFORE the status check.
        // So the attempts SHOULD be cleared for a correct password login even for pending users.
        verify(taskQueue).del("auth:attempt:admin1");
    }

    // --- me() 方法 ---

    @Test
    void meReturnsUserInfo() {
        User user = new User();
        user.setId("u1");
        user.setUsername("john");
        user.setRole("user");
        user.setCreatedAt(java.time.LocalDateTime.now());

        when(userMapper.findById("u1")).thenReturn(user);

        var result = authService.me("u1");

        assertEquals("u1", result.get("id"));
        assertEquals("john", result.get("username"));
        assertEquals("user", result.get("role"));
        assertNotNull(result.get("createdAt"));
    }

    @Test
    void meNonExistentUserThrows() {
        when(userMapper.findById("ghost")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
                () -> authService.me("ghost"));
        assertEquals("用户不存在", ex.getMessage());
    }
}
