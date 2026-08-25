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
        // 使用 4 种字符的强密码，确保通过强度校验
        var result = authService.register("newuser", "Str0ng!Pass", "user");

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

    // ============ 密码强度校验测试 ============

    @Test
    void registerPasswordTooShortThrows() {
        when(userMapper.findByUsername("u1")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "Ab1!", "user"));
        assertEquals("密码长度至少8位", ex.getMessage());
    }

    @Test
    void registerPasswordOnlyOneKindThrows() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        // 只有小写字母，kinds=1
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "abcdefgh", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void registerPasswordTwoKindsWarns() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        // 小写+数字，kinds=2，触发建议消息（不执行 insert，故无需 stub insert）
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "abcd1234", "user"));
        assertTrue(ex.getMessage().contains("建议密码包含大写字母"));
    }

    /**
     * 注意：当前生产代码中，弱密码检查位于 kinds<3 之后，
     * 而弱密码列表中的所有项（password、qwerty123 等）都无法满足 kinds>=3，
     * 因此"密码过于简单"分支实际上不可达。
     * 这里测试弱密码列表中的密码在当前实现下会因 kinds<2 或 kinds<3 被拦截。
     */
    @Test
    void registerWeakPasswordInListIsBlockedByKindsCheck() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        // "qwerty123" 在弱密码列表中，但 kinds=2（小写+数字），触发 kinds<3 建议
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "qwerty123", "user"));
        assertTrue(ex.getMessage().contains("建议密码包含大写字母"));
    }

    @Test
    void registerWeakPasswordSingleKindFailsEarly() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        // "password" 在弱密码列表中，但只有小写字母，kinds=1 → 先被拦截
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "password", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void registerStrongPasswordPasses() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));
        // 大写+小写+数字+特殊，kinds=4
        var result = authService.register("u1", "Str0ng!Pass", "user");
        assertNotNull(result.get("token"));
    }

    // ============ 管理员注册测试 ============

    @Test
    void registerAdminRoleHasPendingStatus() {
        when(userMapper.findByUsername("admin1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("admin1", "Str0ng!Pass", "admin");

        // 管理员申请不返回 token，只返回消息
        assertNull(result.get("token"));
        assertEquals("管理员申请已提交，请等待 root 审批", result.get("message"));
        verify(userMapper).insert(any(User.class));
    }

    @Test
    void registerNullRoleDefaultsToUser() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("u1", "Str0ng!Pass", null);

        assertNotNull(result.get("token"));
        @SuppressWarnings("unchecked")
        var userMap = (java.util.Map<String, Object>) result.get("user");
        assertEquals("user", userMap.get("role"));
    }

    @Test
    void registerArbitraryRoleDefaultsToUser() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("u1", "Str0ng!Pass", "superadmin");

        @SuppressWarnings("unchecked")
        var userMap = (java.util.Map<String, Object>) result.get("user");
        assertEquals("user", userMap.get("role"));
    }

    // ============ 登录锁定逻辑测试 ============

    @Test
    void loginAlreadyLockedThrows() {
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn("locked");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("testuser", "anypass"));
        assertEquals("登录尝试过多，请15分钟后再试", ex.getMessage());
        // 账号已锁定，不应查询用户
        verify(userMapper, never()).findByUsername(anyString());
    }

    @Test
    void loginWrongPasswordFirstFailureRecordsAttempt() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));

        when(userMapper.findByUsername("u1")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:u1")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:u1")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("u1", "wrong"));
        assertTrue(ex.getMessage().contains("还剩4次尝试机会"));
        verify(taskQueue).set(eq("auth:attempt:u1"), eq("1"), eq(30L * 60));
    }

    @Test
    void loginWrongPasswordFourthFailureShowsRemaining() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));

        when(userMapper.findByUsername("u1")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:u1")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:u1")).thenReturn("3");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("u1", "wrong"));
        assertTrue(ex.getMessage().contains("还剩1次尝试机会"));
        verify(taskQueue).set(eq("auth:attempt:u1"), eq("4"), eq(30L * 60));
    }

    @Test
    void loginWrongPasswordFifthFailureLocksAccount() {
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
    void loginUserNotFoundAlsoRecordsAttempt() {
        when(userMapper.findByUsername("nobody")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:lock:nobody")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:nobody")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("nobody", "anypass"));
        assertTrue(ex.getMessage().contains("还剩4次尝试机会"));
    }

    // ============ pending 用户登录 ============

    @Test
    void loginPendingStatusUserThrows() {
        User user = new User();
        user.setId("u1");
        user.setUsername("pendingadmin");
        user.setPassword(encoder.encode("Str0ng!Pass"));
        user.setRole("admin");
        user.setStatus("pending");

        when(userMapper.findByUsername("pendingadmin")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:pendingadmin")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("pendingadmin", "Str0ng!Pass"));
        assertEquals("你的管理员申请正在审批中，请等待 root 审核", ex.getMessage());
        // 即使登录被拒（pending），尝试记录也应在之前被清除（因为密码正确）
        verify(taskQueue).del("auth:attempt:pendingadmin");
        verify(taskQueue).del("auth:lock:pendingadmin");
    }

    @Test
    void loginSuccessClearsBothAttemptAndLock() {
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

    // ============ me() 方法测试 ============

    @Test
    void meWithExistingUserReturnsInfo() {
        User user = new User();
        user.setId("u123");
        user.setUsername("alice");
        user.setRole("user");
        java.time.LocalDateTime ts = java.time.LocalDateTime.of(2025, 1, 1, 0, 0, 0);
        user.setCreatedAt(ts);

        when(userMapper.findById("u123")).thenReturn(user);

        var result = authService.me("u123");
        assertEquals("u123", result.get("id"));
        assertEquals("alice", result.get("username"));
        assertEquals("user", result.get("role"));
        assertEquals(ts, result.get("createdAt"));
    }

    @Test
    void meWithNonExistingUserThrows() {
        when(userMapper.findById("ghost")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.me("ghost"));
        assertEquals("用户不存在", ex.getMessage());
    }
}
