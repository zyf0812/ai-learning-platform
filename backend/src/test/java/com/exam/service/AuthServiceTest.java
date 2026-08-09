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

    // ==================== 密码强度校验 ====================

    @Test
    void registerPasswordTooShortThrows() {
        when(userMapper.findByUsername("u")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "Abc123", "user")); // 6位 < 8
        assertTrue(ex.getMessage().contains("至少8位"));
    }

    @Test
    void registerOnlyDigitsThrowsNeedLettersAndNumbers() {
        when(userMapper.findByUsername("u")).thenReturn(null);
        // 只有数字，没有字母 → 种类=1 < 2
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "1234567890", "user"));
        assertTrue(ex.getMessage().contains("字母和数字"));
    }

    @Test
    void registerOnlyLettersThrowsNeedLettersAndNumbers() {
        when(userMapper.findByUsername("u")).thenReturn(null);
        // 只有字母大小写 → 种类=2 提示需要至少3种
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "Abcdefgh", "user"));
        assertTrue(ex.getMessage().contains("至少3种") || ex.getMessage().contains("字母和数字"));
    }

    @Test
    void registerThreeKindsOkButSoftWarningMessage() {
        when(userMapper.findByUsername("u")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));
        // 大写+小写+数字 = 3种，应该通过（没有返回错误就说明通过了）
        var result = authService.register("u", "HelloWorld2024", "user");
        assertNotNull(result.get("token"));
    }

    @Test
    void registerCommonWeakPasswordThrows() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        var ex1 = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "12345678", "user"));
        assertTrue(ex1.getMessage().contains("过于简单"));

        when(userMapper.findByUsername("u2")).thenReturn(null);
        var ex2 = assertThrows(RuntimeException.class,
            () -> authService.register("u2", "PASSWORD", "user"));
        assertTrue(ex2.getMessage().contains("过于简单") || ex2.getMessage().contains("至少3种"));

        when(userMapper.findByUsername("u3")).thenReturn(null);
        var ex3 = assertThrows(RuntimeException.class,
            () -> authService.register("u3", "qwerty123", "user"));
        assertTrue(ex3.getMessage().contains("过于简单") || ex3.getMessage().contains("至少3种"));
    }

    // ==================== 登录限流 / 锁定 ====================

    @Test
    void loginLockedAccountThrowsImmediateLockMessage() {
        when(taskQueue.getStatus("", "auth:lock:lockeduser")).thenReturn("locked");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("lockeduser", "anypass"));
        assertTrue(ex.getMessage().contains("15分钟"));
        // 账号被锁时不查 DB
        verify(userMapper, never()).findByUsername(anyString());
    }

    @Test
    void loginWrongPasswordFirstTimeShows4Remaining() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));
        when(userMapper.findByUsername("u")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:u")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:u")).thenReturn(null); // 第一次错

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("u", "wrong"));
        assertTrue(ex.getMessage().contains("还剩4次"));
        verify(taskQueue).set(eq("auth:attempt:u"), eq("1"), eq(30L * 60));
    }

    @Test
    void loginWrongPasswordThirdTimeShows2Remaining() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));
        when(userMapper.findByUsername("u")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:u")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:u")).thenReturn("2"); // 已经错了2次

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("u", "wrong"));
        assertTrue(ex.getMessage().contains("还剩2次"));
        verify(taskQueue).set(eq("auth:attempt:u"), eq("3"), eq(30L * 60));
    }

    @Test
    void loginWrongPassword5thTimeLocksAccount() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));
        when(userMapper.findByUsername("u")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:u")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:u")).thenReturn("4"); // 已有4次失败

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("u", "wrong"));
        assertTrue(ex.getMessage().contains("已锁定15分钟"));

        // 触发锁定
        verify(taskQueue).set(eq("auth:lock:u"), eq("locked"), eq(15L * 60));
        verify(taskQueue).del("auth:attempt:u");
    }

    @Test
    void loginSuccessClearsAttemptAndLockKeys() {
        User user = new User();
        user.setId("u1");
        user.setUsername("gooduser");
        user.setPassword(encoder.encode("correct"));
        user.setRole("user");
        user.setStatus("active");

        when(userMapper.findByUsername("gooduser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:gooduser")).thenReturn(null);

        authService.login("gooduser", "correct");

        verify(taskQueue).del("auth:attempt:gooduser");
        verify(taskQueue).del("auth:lock:gooduser");
    }

    // ==================== 管理员注册 & 审批 ====================

    @Test
    void registerAdminRoleGoesToPendingStatus() {
        when(userMapper.findByUsername("newadmin")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("newadmin", "AdminPass2024!", "admin");

        assertEquals("管理员申请已提交，请等待 root 审批", result.get("message"));
        assertNull(result.get("token")); // 审批前不给 token
    }

    @Test
    void registerAdminPendingRoleActuallyStored() {
        when(userMapper.findByUsername("a")).thenReturn(null);

        authService.register("a", "AdminPass2024!", "admin");

        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        User saved = captor.getValue();
        assertEquals("admin", saved.getRole());
        assertEquals("pending", saved.getStatus());
    }

    @Test
    void registerNormalUserRoleDefaultsActiveAndUser() {
        when(userMapper.findByUsername("u")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        authService.register("u", "HelloWorld2024", null);

        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        User saved = captor.getValue();
        assertEquals("user", saved.getRole());
        assertEquals("active", saved.getStatus());
    }

    @Test
    void loginPendingAdminUserThrowsWaitingApproval() {
        User admin = new User();
        admin.setId("a1");
        admin.setUsername("waitadmin");
        admin.setPassword(encoder.encode("AdminPass2024!"));
        admin.setRole("admin");
        admin.setStatus("pending");

        when(userMapper.findByUsername("waitadmin")).thenReturn(admin);
        when(taskQueue.getStatus("", "auth:lock:waitadmin")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("waitadmin", "AdminPass2024!"));
        assertTrue(ex.getMessage().contains("审批中"));
    }

    // ==================== me() ====================

    @Test
    void meReturnsUserInfo() {
        User user = new User();
        user.setId("u1");
        user.setUsername("me");
        user.setRole("user");
        user.setCreatedAt(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));

        when(userMapper.findById("u1")).thenReturn(user);

        var result = authService.me("u1");
        assertEquals("u1", result.get("id"));
        assertEquals("me", result.get("username"));
        assertEquals("user", result.get("role"));
        assertNotNull(result.get("createdAt"));
    }

    @Test
    void meNonExistingUserThrows() {
        when(userMapper.findById("ghost")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class, () -> authService.me("ghost"));
        assertEquals("用户不存在", ex.getMessage());
    }
}
