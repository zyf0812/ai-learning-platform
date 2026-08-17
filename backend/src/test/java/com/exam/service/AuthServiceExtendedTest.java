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

    // ==================== 密码强度校验 ====================

    @Test
    void registerPasswordTooShortThrows() {
        when(userMapper.findByUsername("u1")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "Ab1!", "user"));
        assertEquals("密码长度至少8位", ex.getMessage());
        verify(userMapper, never()).insert(any());
    }

    @Test
    void registerPasswordOnlyLettersFailsLetterDigitRequirement() {
        // 只有字母（大写+小写共2种），但没有数字 → 需要"字母和数字"（种类<2的错误）
        // 注意：hasUpper+hasLower = 2 种 → 种类>=2 不会触发"密码需包含字母和数字"
        // 但让我们测试只有1种（纯小写）的情况
        when(userMapper.findByUsername("u2")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u2", "abcdefgh", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
        verify(userMapper, never()).insert(any());
    }

    @Test
    void registerPasswordOnlyDigitsFailsLetterDigitRequirement() {
        when(userMapper.findByUsername("u3")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u3", "12345678", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
        verify(userMapper, never()).insert(any());
    }

    @Test
    void registerPasswordTwoKindsTriggersStrongerRecommendation() {
        // 2种（小写字母+数字）：种类<3 → "建议密码包含...至少3种"
        when(userMapper.findByUsername("u4")).thenReturn(null);
        // "password1"：全小写字母 + 1个数字 → 恰好2种
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u4", "password1", "user"));
        assertEquals("建议密码包含大写字母、小写字母、数字和特殊字符中的至少3种", ex.getMessage());
        verify(userMapper, never()).insert(any());
    }

    @Test
    void registerPasswordThreeKindsSucceeds() {
        // 大写+小写+数字 = 3种，且非弱密码 → 通过
        when(userMapper.findByUsername("gooduser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("gooduser", "Password123!", "user");
        assertNotNull(result.get("token"));
        verify(userMapper).insert(any(User.class));
    }

    @Test
    void registerWeakPasswordOnBlocklistThrows() {
        // 12345678：虽然只有数字1种（种类<2触发第一条）
        when(userMapper.findByUsername("w1")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("w1", "12345678", "user"));
        // 先触发种类检查
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void registerWeakPasswordPasswordWordThrows() {
        // 使用 Qwerty123：大写+小写+数字=3种，通过种类检查
        // 然后 equalsIgnoreCase("qwerty123") → 命中弱密码黑名单
        when(userMapper.findByUsername("w3")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("w3", "Qwerty123", "user"));
        assertEquals("密码过于简单，请换一个", ex.getMessage());
    }

    // ==================== 管理员注册 pending 流程 ====================

    @Test
    void registerAdminRoleCreatesPendingStatusNoToken() {
        when(userMapper.findByUsername("admin1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("admin1", "Admin@2024", "admin");

        // 不返回 token，返回审批消息
        assertNull(result.get("token"));
        assertEquals("管理员申请已提交，请等待 root 审批", result.get("message"));
        verify(userMapper).insert(argThat(u ->
            "admin".equals(u.getRole()) && "pending".equals(u.getStatus())
        ));
    }

    @Test
    void registerNonAdminRoleDefaultsToUserActive() {
        when(userMapper.findByUsername("normal")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("normal", "Strong@Pass1", "user");

        assertNotNull(result.get("token"));
        verify(userMapper).insert(argThat(u ->
            "user".equals(u.getRole()) && "active".equals(u.getStatus())
        ));
    }

    @Test
    void registerNullRoleDefaultsToUserActive() {
        when(userMapper.findByUsername("norole")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("norole", "Strong@Pass1", null);

        assertNotNull(result.get("token"));
        verify(userMapper).insert(argThat(u ->
            "user".equals(u.getRole()) && "active".equals(u.getStatus())
        ));
    }

    // ==================== 登录限流 / 锁定 ====================

    @Test
    void loginWhenAlreadyLockedThrowsLockMessage() {
        String lockKey = "auth:lock:lockuser";
        when(taskQueue.getStatus("", lockKey)).thenReturn("locked");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("lockuser", "anypass"));
        assertEquals("登录尝试过多，请15分钟后再试", ex.getMessage());

        // 不会查询用户，直接拒
        verify(userMapper, never()).findByUsername(anyString());
    }

    @Test
    void loginWrongPasswordIncrementsAttemptsShowsRemaining() {
        User user = new User();
        user.setPassword(encoder.encode("correctpass"));
        when(userMapper.findByUsername("alice")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:alice")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:alice")).thenReturn(null); // 第1次失败

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("alice", "wrongpass"));
        // 第1次失败：还剩4次
        assertEquals("用户名或密码错误，还剩4次尝试机会", ex.getMessage());
        verify(taskQueue).set(eq("auth:attempt:alice"), eq("1"), eq(30L * 60));
    }

    @Test
    void loginWrongPasswordFourthShowsOneRemaining() {
        User user = new User();
        user.setPassword(encoder.encode("correctpass"));
        when(userMapper.findByUsername("bob")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:bob")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:bob")).thenReturn("3"); // 已有3次失败

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("bob", "wrongpass"));
        // 第4次失败：还剩1次
        assertEquals("用户名或密码错误，还剩1次尝试机会", ex.getMessage());
        verify(taskQueue).set(eq("auth:attempt:bob"), eq("4"), eq(30L * 60));
    }

    @Test
    void loginWrongPasswordFifthLocksAccount() {
        User user = new User();
        user.setPassword(encoder.encode("correctpass"));
        when(userMapper.findByUsername("charlie")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:charlie")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:charlie")).thenReturn("4"); // 已有4次

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("charlie", "wrongpass"));
        assertEquals("密码错误次数过多，账号已锁定15分钟", ex.getMessage());

        verify(taskQueue).set(eq("auth:lock:charlie"), eq("locked"), eq(15L * 60));
        verify(taskQueue).del(eq("auth:attempt:charlie"));
    }

    @Test
    void loginSuccessClearsAttemptsAndLock() {
        User user = new User();
        user.setId("u-x");
        user.setUsername("dave");
        user.setPassword(encoder.encode("correctpass"));
        user.setRole("user");
        user.setStatus("active");
        when(userMapper.findByUsername("dave")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:dave")).thenReturn(null);

        var result = authService.login("dave", "correctpass");

        assertNotNull(result.get("token"));
        verify(taskQueue).del("auth:attempt:dave");
        verify(taskQueue).del("auth:lock:dave");
    }

    // ==================== pending 用户登录被拒 ====================

    @Test
    void loginPendingAdminUserThrowsApprovalMessage() {
        User user = new User();
        user.setId("p1");
        user.setPassword(encoder.encode("Admin@2024"));
        user.setRole("admin");
        user.setStatus("pending");
        when(userMapper.findByUsername("pendingadmin")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:pendingadmin")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("pendingadmin", "Admin@2024"));
        assertEquals("你的管理员申请正在审批中，请等待 root 审核", ex.getMessage());
    }

    // ==================== me() 方法 ====================

    @Test
    void meExistingUserReturnsProfile() {
        User user = new User();
        user.setId("u123");
        user.setUsername("eve");
        user.setRole("user");
        user.setCreatedAt(java.time.LocalDateTime.now());
        when(userMapper.findById("u123")).thenReturn(user);

        var result = authService.me("u123");

        assertEquals("u123", result.get("id"));
        assertEquals("eve", result.get("username"));
        assertEquals("user", result.get("role"));
        assertNotNull(result.get("createdAt"));
    }

    @Test
    void meNonExistingUserThrows() {
        when(userMapper.findById("ghost")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.me("ghost"));
        assertEquals("用户不存在", ex.getMessage());
    }
}
