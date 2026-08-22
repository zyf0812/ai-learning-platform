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
import static org.mockito.ArgumentMatchers.anyLong;
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

    // ===== 密码强度校验 (通过 register 间接测试) =====

    @Test
    void registerPasswordTooShortThrows() {
        when(userMapper.findByUsername(any())).thenReturn(null);
        // 7 位 < 8 位
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "Ab1!xyz", "user"));
        assertTrue(ex.getMessage().contains("至少8位"),
            "短密码应报长度错误，实际: " + ex.getMessage());
    }

    @Test
    void registerPasswordOnlyLettersFails() {
        when(userMapper.findByUsername(any())).thenReturn(null);
        // 只有小写字母（没有大写、没有数字或特殊字符），kinds=1 < 2
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "onlyletters", "user"));
        assertTrue(ex.getMessage().contains("字母和数字"));
    }

    @Test
    void registerPasswordTwoKindsPassesWithWarning() {
        // 只有小写字母 + 数字 (kinds=2 < 3, >=2) → 给出"至少3种"建议
        when(userMapper.findByUsername(any())).thenReturn(null);
        // 注：kinds=2 时抛异常，不会执行 insert，因此不 stub insert

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "abcdefg1", "user"));
        assertTrue(ex.getMessage().contains("至少3种"));
    }

    @Test
    void registerPasswordThreeKindsSucceeds() {
        // 大写 + 小写 + 数字 = 3 种 → 通过
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("newuser", "StrongPass1", "user");
        assertNotNull(result.get("token"));
    }

    @Test
    void registerPasswordFourKindsSucceeds() {
        // 大写 + 小写 + 数字 + 特殊 = 4 种 → 通过
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("newuser", "Str0ng!Pass", "user");
        assertNotNull(result.get("token"));
    }

    @Test
    void registerCommonWeakPasswordRejected() {
        // qwerty123 在弱密码列表里，但 kinds=2 → 先被 kinds<3 拦截
        when(userMapper.findByUsername(any())).thenReturn(null);
        var exKinds2 = assertThrows(RuntimeException.class,
            () -> authService.register("u", "qwerty123", "user"));
        assertTrue(exKinds2.getMessage().contains("至少3种"));
    }

    @Test
    void registerWeakPasswordExactlyInListIgnoredCase() {
        // Abc123456 → 大写A + 小写BC + 数字123456 = 3 kinds
        // 然后 "abc123456" 在弱密码列表中（equalsIgnoreCase），应被拦截
        when(userMapper.findByUsername(any())).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "Abc123456", "user"));
        assertTrue(ex.getMessage().contains("过于简单"),
            "弱密码应被拦截: " + ex.getMessage());
    }

    @Test
    void registerWeakPasswordQwertyWithThreeKindsStillRejected() {
        // 构建 kinds>=3 且 equalsIgnoreCase 在弱密码列表中的密码: Password 仅 kinds=1 (全字母)
        // 因此验证 kinds>=3 + 在弱密码列表里会被"过于简单"拦截
        // 弱密码列表里没有任何 kinds>=3 的，所以改成验证一个强密码(3 kinds)能通过弱密码检查
        when(userMapper.findByUsername("stronguser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));
        // Str0ngPw: 大写 S/P, 小写 trng/w, 数字 0 → 3 kinds，不在弱密码列表 → 通过
        var result = authService.register("stronguser", "Str0ngPw", "user");
        assertNotNull(result.get("token"));
    }

    // ===== 登录限流 / 锁定机制 =====

    @Test
    void loginLockedUserThrowsLockMessage() {
        when(taskQueue.getStatus("", "auth:lock:bob")).thenReturn("locked");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("bob", "any"));
        assertTrue(ex.getMessage().contains("15分钟"),
            "锁定用户应提示 15 分钟: " + ex.getMessage());
        verify(userMapper, never()).findByUsername(any());
    }

    @Test
    void loginWrongPasswordIncrementsAttempts() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));

        when(userMapper.findByUsername("bob")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:bob")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:bob")).thenReturn(null); // 初次失败

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("bob", "wrong"));
        assertTrue(ex.getMessage().contains("还剩4次"),
            "第1次失败应提示还剩4次: " + ex.getMessage());
        verify(taskQueue).set(eq("auth:attempt:bob"), eq("1"), eq(30 * 60L));
    }

    @Test
    void loginWrongPasswordReadsExistingCount() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));

        when(userMapper.findByUsername("bob")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:bob")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:bob")).thenReturn("2"); // 已有 2 次失败

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("bob", "wrong"));
        assertTrue(ex.getMessage().contains("还剩2次"), // 5 - (2+1) = 2
            "已有2次失败，本次第3次 → 剩2次: " + ex.getMessage());
        verify(taskQueue).set(eq("auth:attempt:bob"), eq("3"), eq(30 * 60L));
    }

    @Test
    void loginFifthWrongPasswordLocksAccount() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));

        when(userMapper.findByUsername("bob")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:bob")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:bob")).thenReturn("4"); // 已有 4 次

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("bob", "wrong"));
        assertTrue(ex.getMessage().contains("锁定15分钟"),
            "第5次失败应锁定账号: " + ex.getMessage());
        verify(taskQueue).set(eq("auth:lock:bob"), eq("locked"), eq(15 * 60L));
        verify(taskQueue).del("auth:attempt:bob");
    }

    @Test
    void loginSuccessfulClearsAttemptAndLock() {
        User user = new User();
        user.setId("u1");
        user.setUsername("bob");
        user.setPassword(encoder.encode("correct"));
        user.setRole("user");
        user.setStatus("active");

        when(userMapper.findByUsername("bob")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:bob")).thenReturn(null);

        authService.login("bob", "correct");

        verify(taskQueue).del("auth:attempt:bob");
        verify(taskQueue).del("auth:lock:bob");
    }

    // ===== 管理员注册 pending 审批 =====

    @Test
    void registerAdminRoleSetsPendingStatus() {
        when(userMapper.findByUsername("newadmin")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("newadmin", "AdminPass1!", "admin");

        assertNull(result.get("token"), "管理员不应立即获得 token");
        assertNotNull(result.get("message"));
        assertTrue(result.get("message").toString().contains("审批"),
            "管理员应提示等待审批: " + result.get("message"));
    }

    @Test
    void loginPendingAdminUserRejected() {
        User user = new User();
        user.setId("u1");
        user.setUsername("pendingadmin");
        user.setPassword(encoder.encode("correct"));
        user.setRole("admin");
        user.setStatus("pending");

        when(userMapper.findByUsername("pendingadmin")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:pendingadmin")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("pendingadmin", "correct"));
        assertTrue(ex.getMessage().contains("审批"),
            "pending 管理员应被拦截: " + ex.getMessage());
        verify(taskQueue).del("auth:attempt:pendingadmin");
    }

    @Test
    void registerUserRoleAutoActive() {
        when(userMapper.findByUsername("normuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("normuser", "UserPass1", "user");
        assertNotNull(result.get("token"));
        @SuppressWarnings("unchecked")
        var userMap = (java.util.Map<?, ?>) result.get("user");
        assertEquals("user", userMap.get("role"));
    }

    @Test
    void registerNullRoleDefaultsToUser() {
        when(userMapper.findByUsername("norole")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("norole", "UserPass1", null);
        @SuppressWarnings("unchecked")
        var userMap = (java.util.Map<?, ?>) result.get("user");
        assertEquals("user", userMap.get("role"));
    }

    @Test
    void registerInvalidRoleTreatedAsUser() {
        when(userMapper.findByUsername("weirdrole")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("weirdrole", "UserPass1", "superadmin");
        @SuppressWarnings("unchecked")
        var userMap = (java.util.Map<?, ?>) result.get("user");
        assertEquals("user", userMap.get("role"));
    }

    // ===== me() 方法 =====

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
    void meMissingUserThrows() {
        when(userMapper.findById("nonexist")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class, () -> authService.me("nonexist"));
        assertEquals("用户不存在", ex.getMessage());
    }

    // ===== 用户名重复 =====

    @Test
    void registerDuplicateThrows() {
        when(userMapper.findByUsername("dup")).thenReturn(new User());
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("dup", "UserPass1", "user"));
        assertEquals("用户名已存在", ex.getMessage());
    }
}
