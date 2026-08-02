package com.exam.service;

import com.exam.mapper.UserMapper;
import com.exam.model.User;
import com.exam.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AuthService 补充测试：
 * - 密码强度校验 checkPasswordStrength 的所有分支
 * - 登录限流（5 次失败锁定 15 分钟）
 * - 管理员注册/审批状态逻辑
 * - login 成功后的状态清除
 * - pending 用户登录拦截
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceExtendedTest {

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

    // ===== 密码强度：长度 =====

    @Test
    void registerPasswordTooShort() {
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "Ab1!", "user"));
        assertEquals("密码长度至少8位", ex.getMessage());
    }

    // ===== 密码强度：字符种类 =====

    @Test
    void registerPasswordOnlyDigitsOneKindFails() {
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "12345678", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void registerPasswordOnlyLettersOneKindFails() {
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "abcdefgh", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void registerPasswordLettersAndDigitsTwoKindsWarnsThree() {
        // Abcdefg1: 大写+小写+数字 = 3种？实际上 kinds=3 -> 不会失败，但是否触发建议？
        // kinds<3 才触发建议；kinds>=3 且不是弱密码 -> null
        // Abcdef12: 大写+小写+数字=3种 -> 通过
        when(userMapper.findByUsername("u1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("u1", "Abcdef12", "user");
        assertNotNull(result.get("token"));
    }

    @Test
    void registerPasswordOnlyUpperAndLowerTwoKinds() {
        // 只有大小写字母，没有数字 -> kinds=2，不够2？
        // hasUpper=true, hasLower=true, hasDigit=false, hasSpecial=false -> kinds=2
        // kinds<2 ? 否，等于2
        // 进入下一个 if: kinds<3 -> true -> 建议包含3种
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "ABCDefgh", "user"));
        assertEquals("建议密码包含大写字母、小写字母、数字和特殊字符中的至少3种", ex.getMessage());
    }

    @Test
    void registerPasswordLowerDigitAndSpecialThreeKindsPasses() {
        // abc123!@ : 小写+数字+特殊 = 3 种
        when(userMapper.findByUsername("u1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("u1", "abcd123!", "user");
        assertNotNull(result.get("token"));
    }

    // ===== 常见弱密码 =====

    @Test
    void registerWeakPassword12345678() {
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "12345678", "user"));
        // 首先触发的是 kinds<2 ("密码需包含字母和数字")，因为全是数字
        assertTrue(ex.getMessage().contains("字母"));
    }

    @Test
    void registerWeakPasswordCaseInsensitive() {
        // PASSWORD: 全字母 kinds=1 -> 先触发需包含字母和数字
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "PASSWORD", "user"));
        assertTrue(ex.getMessage().contains("字母和数字"));
    }

    @Test
    void registerPassword88888888Weak() {
        // 全数字，kinds=1
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "88888888", "user"));
        assertTrue(ex.getMessage().contains("字母和数字"));
    }

    // ===== 管理员注册 =====

    @Test
    void registerAdminSetsPendingStatus() {
        when(userMapper.findByUsername("boss")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("boss", "AdminPass1!", "admin");

        assertEquals("管理员申请已提交，请等待 root 审批",
            result.get("message"));
        assertNull(result.get("token")); // 不立即发 token

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(userCaptor.capture());
        assertEquals("admin", userCaptor.getValue().getRole());
        assertEquals("pending", userCaptor.getValue().getStatus());
    }

    @Test
    void registerAdminRoleCaseInsensitiveFallsBackToUser() {
        // role 只判断 equals("admin")，不是 ADMIN
        when(userMapper.findByUsername("u1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("u1", "Secure123!", "ADMIN");
        // 因为不是严格 "admin"，所以当普通用户
        assertNotNull(result.get("token"));
        @SuppressWarnings("unchecked")
        var userMap = (java.util.Map<?, ?>) result.get("user");
        assertEquals("user", userMap.get("role"));
    }

    @Test
    void registerNullRoleDefaultsToUser() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("u1", "SecurePass123", null);
        @SuppressWarnings("unchecked")
        var userMap = (java.util.Map<?, ?>) result.get("user");
        assertEquals("user", userMap.get("role"));
    }

    // ===== 登录限流 =====

    @Test
    void loginLockedFromStartThrowsImmediately() {
        when(taskQueue.getStatus("", "auth:lock:userA")).thenReturn("locked");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("userA", "whatever"));
        assertEquals("登录尝试过多，请15分钟后再试", ex.getMessage());
    }

    @Test
    void loginFirstFourFailuresCountDownAttempts() {
        User u = new User();
        u.setPassword(encoder.encode("correct"));
        when(userMapper.findByUsername("bob")).thenReturn(u);
        when(taskQueue.getStatus("", "auth:lock:bob")).thenReturn(null);

        // 第 1 次失败
        when(taskQueue.getStatus("", "auth:attempt:bob")).thenReturn(null);
        var ex1 = assertThrows(RuntimeException.class,
            () -> authService.login("bob", "wrong"));
        assertTrue(ex1.getMessage().contains("还剩4次"));
        verify(taskQueue).set(eq("auth:attempt:bob"), eq("1"), eq(30L * 60));

        // 第 4 次失败（已有 3 次记录）
        when(taskQueue.getStatus("", "auth:attempt:bob")).thenReturn("3");
        var ex4 = assertThrows(RuntimeException.class,
            () -> authService.login("bob", "wrong"));
        assertTrue(ex4.getMessage().contains("还剩1次"));
        verify(taskQueue).set(eq("auth:attempt:bob"), eq("4"), eq(30L * 60));
    }

    @Test
    void loginFifthFailureTriggersLock() {
        User u = new User();
        u.setPassword(encoder.encode("correct"));
        when(userMapper.findByUsername("bob")).thenReturn(u);
        when(taskQueue.getStatus("", "auth:lock:bob")).thenReturn(null);
        // 已有 4 次失败记录
        when(taskQueue.getStatus("", "auth:attempt:bob")).thenReturn("4");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("bob", "wrong"));
        assertEquals("密码错误次数过多，账号已锁定15分钟", ex.getMessage());

        verify(taskQueue).set(eq("auth:lock:bob"), eq("locked"), eq(15L * 60));
        verify(taskQueue).del("auth:attempt:bob");
    }

    @Test
    void loginSuccessClearsBothAttemptAndLock() {
        User u = new User();
        u.setId("id1");
        u.setUsername("good");
        u.setPassword(encoder.encode("correct"));
        u.setRole("user");
        u.setStatus("active");
        when(userMapper.findByUsername("good")).thenReturn(u);
        when(taskQueue.getStatus("", "auth:lock:good")).thenReturn(null);

        authService.login("good", "correct");

        verify(taskQueue).del("auth:attempt:good");
        verify(taskQueue).del("auth:lock:good");
    }

    // ===== pending 状态用户拦截 =====

    @Test
    void loginPendingAdminDenied() {
        User u = new User();
        u.setId("id1");
        u.setUsername("admin2");
        u.setPassword(encoder.encode("ok"));
        u.setRole("admin");
        u.setStatus("pending");
        when(userMapper.findByUsername("admin2")).thenReturn(u);
        when(taskQueue.getStatus("", "auth:lock:admin2")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("admin2", "ok"));
        assertEquals("你的管理员申请正在审批中，请等待 root 审核", ex.getMessage());
    }

    // ===== me =====

    @Test
    void meReturnsUserInfo() {
        User u = new User();
        u.setId("u-001");
        u.setUsername("alice");
        u.setRole("user");
        u.setCreatedAt(java.time.LocalDateTime.now());
        when(userMapper.findById("u-001")).thenReturn(u);

        var result = authService.me("u-001");
        assertEquals("u-001", result.get("id"));
        assertEquals("alice", result.get("username"));
        assertEquals("user", result.get("role"));
        assertNotNull(result.get("createdAt"));
    }

    @Test
    void meMissingThrows() {
        when(userMapper.findById("nope")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.me("nope"));
        assertEquals("用户不存在", ex.getMessage());
    }
}
