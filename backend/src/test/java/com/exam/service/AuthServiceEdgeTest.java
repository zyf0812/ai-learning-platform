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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AuthService 边界条件测试，补充现有 AuthServiceTest 未覆盖的：
 *  - 密码强度校验（长度、复杂度、弱密码列表）
 *  - 管理员注册审批流程（status=pending）
 *  - 登录限流 / 5次失败锁定
 *  - pending 状态用户禁止登录
 *  - me() 方法
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceEdgeTest {

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

    // ========== 密码强度：长度 ==========

    @Test
    void registerPasswordTooShortThrows() {
        when(userMapper.findByUsername("u")).thenReturn(null);
        // 7 位，< 8
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "Ab12345", "user"));
        assertEquals("密码长度至少8位", ex.getMessage());
    }

    @Test
    void registerPasswordExactly8CharsMin() {
        // 恰好 8 位，且包含大小写+数字 (3种)
        when(userMapper.findByUsername("u")).thenReturn(null);
        doNothing().when(userMapper).insert(any());
        var r = authService.register("u", "Abc12345", "user");
        assertNotNull(r.get("token"));
    }

    // ========== 密码强度：复杂度 < 2 ==========

    @Test
    void registerPasswordOnlyLettersFails() {
        when(userMapper.findByUsername("u")).thenReturn(null);
        // 全字母（大小写两种 = kinds=2，满足最低）
        // 仅小写字母 kinds=1 < 2
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "abcdefgh", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void registerPasswordOnlyDigitsFails() {
        when(userMapper.findByUsername("u")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "12345678", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    // ========== 密码强度：kinds == 2 的警告（实际不抛错，kinds<3 才抛）==========

    @Test
    void registerPasswordKindsExactly2Warns() {
        // 小写字母 + 数字 = 2 kinds → 抛提示包含至少3种
        when(userMapper.findByUsername("u")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "abcd1234", "user"));
        assertTrue(ex.getMessage().contains("至少3种"));
    }

    @Test
    void registerPasswordKinds3Passes() {
        // 大写 + 小写 + 数字 = 3 kinds
        when(userMapper.findByUsername("u")).thenReturn(null);
        doNothing().when(userMapper).insert(any());
        var r = authService.register("u", "Abcd1234", "user");
        assertNotNull(r.get("token"));
    }

    @Test
    void registerPasswordWithSpecialCharCountsAsKind() {
        // 小写 + 数字 + 特殊 = 3 kinds（无大写也通过）
        when(userMapper.findByUsername("u")).thenReturn(null);
        doNothing().when(userMapper).insert(any());
        var r = authService.register("u", "abcd1234!", "user");
        assertNotNull(r.get("token"));
    }

    // ========== 密码强度：常见弱密码 ==========

    @Test
    void registerWeakPasswordRejected() {
        when(userMapper.findByUsername("u")).thenReturn(null);
        // "Abc123456": 大写A + 小写bc + 数字123456 = 3 kinds，equalsIgnoreCase("abc123456") = true
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "Abc123456", "user"));
        assertEquals("密码过于简单，请换一个", ex.getMessage());
    }

    @Test
    void registerWeakPasswordCaseInsensitive() {
        when(userMapper.findByUsername("u")).thenReturn(null);
        // "Qwerty123": 大写Q + 小写werty + 数字123 = 3 kinds，equalsIgnoreCase("qwerty123") = true
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "Qwerty123", "user"));
        assertEquals("密码过于简单，请换一个", ex.getMessage());
    }

    // ========== 管理员注册：status=pending ==========

    @Test
    void registerAdminReturnsPendingMessageNotToken() {
        when(userMapper.findByUsername("admin1")).thenReturn(null);
        doNothing().when(userMapper).insert(any());

        var r = authService.register("admin1", "Abcd1234!", "admin");
        assertNull(r.get("token"));
        assertEquals("管理员申请已提交，请等待 root 审批", r.get("message"));
    }

    @Test
    void registerAdminStatusIsPending() {
        when(userMapper.findByUsername("admin1")).thenReturn(null);

        authService.register("admin1", "Abcd1234!", "admin");

        var captor = argumentCaptor();
        verify(userMapper).insert(captor.capture());
        User saved = captor.getValue();
        assertEquals("admin", saved.getRole());
        assertEquals("pending", saved.getStatus());
    }

    @Test
    void registerNonAdminStatusIsActive() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        doNothing().when(userMapper).insert(any());

        authService.register("u1", "Abcd1234!", "user");

        var captor = argumentCaptor();
        verify(userMapper).insert(captor.capture());
        assertEquals("user", captor.getValue().getRole());
        assertEquals("active", captor.getValue().getStatus());
    }

    @Test
    void registerInvalidRoleDefaultsToUser() {
        // role 既不是 admin 也不是 user → 默认 user
        when(userMapper.findByUsername("u1")).thenReturn(null);
        doNothing().when(userMapper).insert(any());

        authService.register("u1", "Abcd1234!", "hacker");

        var captor = argumentCaptor();
        verify(userMapper).insert(captor.capture());
        assertEquals("user", captor.getValue().getRole());
    }

    @SuppressWarnings("unchecked")
    private static org.mockito.ArgumentCaptor<User> argumentCaptor() {
        return org.mockito.ArgumentCaptor.forClass(User.class);
    }

    // ========== 登录限流：第 1~4 次失败 ==========

    @Test
    void loginFirstFailureShowsRemaining4() {
        User u = new User();
        u.setUsername("t");
        u.setPassword(encoder.encode("correct"));
        u.setStatus("active");
        when(userMapper.findByUsername("t")).thenReturn(u);
        when(taskQueue.getStatus("", "auth:lock:t")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:t")).thenReturn(null); // 第1次失败

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("t", "wrong"));
        assertTrue(ex.getMessage().contains("还剩4次"));

        verify(taskQueue).set(eq("auth:attempt:t"), eq("1"), eq(30L * 60));
    }

    @Test
    void login4thFailureShowsRemaining1() {
        User u = new User();
        u.setPassword(encoder.encode("correct"));
        u.setStatus("active");
        when(userMapper.findByUsername("t")).thenReturn(u);
        when(taskQueue.getStatus("", "auth:lock:t")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:t")).thenReturn("3"); // 累计 3 次，本次 = 第4次

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("t", "wrong"));
        assertTrue(ex.getMessage().contains("还剩1次"));

        verify(taskQueue).set(eq("auth:attempt:t"), eq("4"), eq(30L * 60));
    }

    // ========== 登录限流：第 5 次失败 → 锁定 ==========

    @Test
    void login5thFailureLocksAccount15Minutes() {
        User u = new User();
        u.setPassword(encoder.encode("correct"));
        u.setStatus("active");
        when(userMapper.findByUsername("t")).thenReturn(u);
        when(taskQueue.getStatus("", "auth:lock:t")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:t")).thenReturn("4"); // 累计 4 次，本次 = 第5次

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("t", "wrong"));
        assertTrue(ex.getMessage().contains("账号已锁定15分钟"));

        // 验证锁 15 分钟 + 清除 attempt 计数
        verify(taskQueue).set(eq("auth:lock:t"), eq("locked"), eq(15L * 60));
        verify(taskQueue).del("auth:attempt:t");
    }

    // ========== 已锁定状态下登录 ==========

    @Test
    void loginWhileLockedRejectsImmediately() {
        when(taskQueue.getStatus("", "auth:lock:t")).thenReturn("locked");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("t", "any"));
        assertTrue(ex.getMessage().contains("登录尝试过多，请15分钟后再试"));

        // 不应查询 DB
        verify(userMapper, never()).findByUsername(any());
    }

    // ========== 登录成功：清除锁定和计数 ==========

    @Test
    void loginSuccessClearsAttemptAndLockRecords() {
        User u = new User();
        u.setId("u1");
        u.setUsername("t");
        u.setPassword(encoder.encode("correct"));
        u.setRole("user");
        u.setStatus("active");
        when(userMapper.findByUsername("t")).thenReturn(u);
        when(taskQueue.getStatus("", "auth:lock:t")).thenReturn(null);

        var r = authService.login("t", "correct");
        assertNotNull(r.get("token"));

        verify(taskQueue).del("auth:attempt:t");
        verify(taskQueue).del("auth:lock:t");
    }

    // ========== pending 用户登录 ==========

    @Test
    void loginPendingAdminUserThrows() {
        User u = new User();
        u.setId("u1");
        u.setPassword(encoder.encode("correct"));
        u.setStatus("pending");
        when(userMapper.findByUsername("a")).thenReturn(u);
        when(taskQueue.getStatus("", "auth:lock:a")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("a", "correct"));
        assertTrue(ex.getMessage().contains("审批中"));
    }

    // ========== me() 方法 ==========

    @Test
    void meReturnsUserInfo() {
        User u = new User();
        u.setId("u1");
        u.setUsername("alice");
        u.setRole("user");
        u.setCreatedAt(java.time.LocalDateTime.now());
        when(userMapper.findById("u1")).thenReturn(u);

        var r = authService.me("u1");
        assertEquals("u1", r.get("id"));
        assertEquals("alice", r.get("username"));
        assertEquals("user", r.get("role"));
        assertNotNull(r.get("createdAt"));
    }

    @Test
    void meMissingUserThrows() {
        when(userMapper.findById("nope")).thenReturn(null);
        assertThrows(RuntimeException.class, () -> authService.me("nope"));
    }
}
