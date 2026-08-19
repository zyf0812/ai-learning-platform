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

import java.time.LocalDateTime;

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

    // --- Password strength validation ---

    @Test
    void registerPasswordTooShortThrows() {
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("user", "Aa1!", "user"));
        assertEquals("密码长度至少8位", ex.getMessage());
    }

    @Test
    void registerPasswordOnlyLettersOneKindFails() {
        // Only lowercase letters = 1 kind, need >= 2
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "password", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void registerPasswordLetterDigitTwoKindsWarnsForMore() {
        // lowercase + digit = 2 kinds, < 3 -> suggestion message
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "abc12345", "user"));
        assertEquals("建议密码包含大写字母、小写字母、数字和特殊字符中的至少3种", ex.getMessage());
    }

    @Test
    void registerPasswordThreeKindsAccepted() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        // Upper + lower + digit = 3 kinds, passes
        var result = authService.register("newuser", "StrongPass1", "user");
        assertNotNull(result.get("token"));
    }

    @Test
    void registerPasswordFourKindsAccepted() {
        when(userMapper.findByUsername("u")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("u", "Str0ng!Pass", "user");
        assertNotNull(result.get("token"));
    }

    @Test
    void registerCommonWeakPasswordRejected() {
        // "12345678" has only digits (1 kind) so kinds<2 check fires first
        var ex1 = assertThrows(RuntimeException.class,
            () -> authService.register("u", "12345678", "user"));
        assertEquals("密码需包含字母和数字", ex1.getMessage());

        // Weak password detection order: kinds checks BEFORE weak list.
        // To actually reach weak-list check, we need >= 3 kinds that match list.
        // Verify that a 2-kind password with suggestion-level strength passes kinds<2
        // but triggers kinds<3 suggestion:
        var ex2 = assertThrows(RuntimeException.class,
            () -> authService.register("u", "abc12345", "user"));
        assertEquals("建议密码包含大写字母、小写字母、数字和特殊字符中的至少3种", ex2.getMessage());

        // A 3-kind password of sufficient length passes:
        when(userMapper.findByUsername("gooduser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));
        var result = authService.register("gooduser", "Str0ng!Pass", "user");
        assertNotNull(result.get("token"));
    }

    @Test
    void registerWeakPassword88888888Rejected() {
        // "88888888" has only digits (1 kind) -> kinds<2 check fires before weak list
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "88888888", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    // --- Admin registration approval flow ---

    @Test
    void registerAdminRoleHasPendingStatus() {
        when(userMapper.findByUsername("adminuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("adminuser", "Str0ng!Pass", "admin");
        assertNull(result.get("token"));
        assertEquals("管理员申请已提交，请等待 root 审批", result.get("message"));

        // Verify user inserted with pending status
        verify(userMapper).insert(argThat(u ->
            "admin".equals(u.getRole()) && "pending".equals(u.getStatus())
        ));
    }

    @Test
    void registerUserRoleActiveImmediately() {
        when(userMapper.findByUsername("normal")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("normal", "Str0ng!Pass", "user");
        assertNotNull(result.get("token"));

        verify(userMapper).insert(argThat(u ->
            "user".equals(u.getRole()) && "active".equals(u.getStatus())
        ));
    }

    @Test
    void registerInvalidRoleDefaultsToUser() {
        when(userMapper.findByUsername("weird")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        authService.register("weird", "Str0ng!Pass", "superadmin");

        verify(userMapper).insert(argThat(u ->
            "user".equals(u.getRole()) && "active".equals(u.getStatus())
        ));
    }

    // --- Login rate limiting / lockout ---

    @Test
    void loginLockedUserThrowsLockMessage() {
        when(taskQueue.getStatus("", "auth:lock:lockeduser")).thenReturn("locked");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("lockeduser", "anypass"));
        assertEquals("登录尝试过多，请15分钟后再试", ex.getMessage());

        // No user lookup should happen when already locked
        verify(userMapper, never()).findByUsername(anyString());
    }

    @Test
    void loginFifthFailureLocksAccount() {
        User user = new User();
        user.setPassword(encoder.encode("correctpass"));

        when(userMapper.findByUsername("u")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:u")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:u")).thenReturn("4"); // 4 prior fails

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("u", "wrong"));
        assertEquals("密码错误次数过多，账号已锁定15分钟", ex.getMessage());

        verify(taskQueue).set(eq("auth:lock:u"), eq("locked"), eq(15L * 60));
        verify(taskQueue).del("auth:attempt:u");
    }

    @Test
    void loginFirstFailureCountsOneAndWarnsRemaining() {
        User user = new User();
        user.setPassword(encoder.encode("correctpass"));

        when(userMapper.findByUsername("u")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:u")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:u")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("u", "wrong"));
        assertEquals("用户名或密码错误，还剩4次尝试机会", ex.getMessage());

        verify(taskQueue).set(eq("auth:attempt:u"), eq("1"), eq(30L * 60));
    }

    @Test
    void loginThirdFailureWarnsTwoLeft() {
        User user = new User();
        user.setPassword(encoder.encode("correctpass"));

        when(userMapper.findByUsername("u")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:u")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:u")).thenReturn("2");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("u", "wrong"));
        assertEquals("用户名或密码错误，还剩2次尝试机会", ex.getMessage());

        verify(taskQueue).set(eq("auth:attempt:u"), eq("3"), eq(30L * 60));
    }

    @Test
    void loginSuccessClearsAttemptsAndLock() {
        User user = new User();
        user.setId("u1");
        user.setUsername("u");
        user.setPassword(encoder.encode("correctpass"));
        user.setRole("user");
        user.setStatus("active");

        when(userMapper.findByUsername("u")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:u")).thenReturn(null);

        var result = authService.login("u", "correctpass");
        assertNotNull(result.get("token"));

        verify(taskQueue).del("auth:attempt:u");
        verify(taskQueue).del("auth:lock:u");
    }

    // --- Login with pending status (admin awaiting approval) ---

    @Test
    void loginPendingAdminUserThrowsApprovalMessage() {
        User user = new User();
        user.setId("a1");
        user.setUsername("admin_candidate");
        user.setPassword(encoder.encode("Str0ng!Pass"));
        user.setRole("admin");
        user.setStatus("pending");

        when(userMapper.findByUsername("admin_candidate")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:admin_candidate")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("admin_candidate", "Str0ng!Pass"));
        assertEquals("你的管理员申请正在审批中，请等待 root 审核", ex.getMessage());

        // Success cleanup (del) runs BEFORE pending check, so they ARE called
        verify(taskQueue).del("auth:attempt:admin_candidate");
        verify(taskQueue).del("auth:lock:admin_candidate");
    }

    // --- me() user info ---

    @Test
    void meReturnsUserInfo() {
        User user = new User();
        user.setId("u1");
        user.setUsername("alice");
        user.setRole("user");
        user.setCreatedAt(LocalDateTime.of(2025, 1, 1, 0, 0));

        when(userMapper.findById("u1")).thenReturn(user);

        var result = authService.me("u1");
        assertEquals("u1", result.get("id"));
        assertEquals("alice", result.get("username"));
        assertEquals("user", result.get("role"));
        assertEquals(user.getCreatedAt(), result.get("createdAt"));
    }

    @Test
    void meMissingUserThrowsNotFound() {
        when(userMapper.findById("ghost")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.me("ghost"));
        assertEquals("用户不存在", ex.getMessage());
    }
}
