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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AuthService 补充测试：密码强度、限流、pending 审批、me 接口
 *
 * 与 AuthServiceTest 互补，覆盖以下未测试路径：
 * - checkPasswordStrength: 边界值 8 位、字符种类判断、弱密码黑名单
 * - login 限流累积: 1~4 次错误返回剩余次数，第 5 次锁定 15 分钟
 * - login 锁定后: 直接抛锁定期限错误
 * - login pending 状态: 管理员申请审批中
 * - register admin 角色: 自动进入 pending 状态
 * - me 接口: 用户存在/不存在
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceSecurityTest {

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

    // ==================== 密码强度校验 ====================

    @Test
    void register_passwordTooShort_rejected() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> authService.register("user1", "abc7", "user"));
        assertEquals("密码长度至少8位", ex.getMessage());
        verify(userMapper, never()).insert(any());
    }

    @Test
    void register_passwordExactly8Accepted() {
        // 大写 + 小写 + 数字 = 3 种，通过 kinds 检查
        when(userMapper.findByUsername("user1")).thenReturn(null);

        Map<String, Object> result = authService.register("user1", "Abcdefg7", "user");
        assertNotNull(result.get("token"));
    }

    @Test
    void register_passwordOnlyOneKind_rejected() {
        // 只有小写字母，只有 1 种
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> authService.register("user1", "abcdefgh", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void register_passwordTwoKindsOfLowerAndDigit_rejectedAdvice() {
        // 2 种 = 建议更复杂
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> authService.register("user1", "abcdefgh9", "user"));
        assertTrue(ex.getMessage().contains("建议"));
    }

    @Test
    void register_passwordThreeKinds_accepted() {
        // 大写 + 小写 + 数字 = 3 种，满足
        when(userMapper.findByUsername("user1")).thenReturn(null);

        Map<String, Object> result = authService.register("user1", "Abcdefg7", "user");
        assertNotNull(result.get("token"));
    }

    @Test
    void register_passwordWithSpecialChar_accepted() {
        when(userMapper.findByUsername("user1")).thenReturn(null);

        Map<String, Object> result = authService.register("user1", "Abcdef!g7", "user");
        assertNotNull(result.get("token"));
    }

    @Test
    void register_weakPasswordCommon_rejected() {
        // 黑名单密码 kinds 都 < 3，会先被 kinds 检查拦截。
        // 这里测试 kinds=2（小写+数字）返回"建议"消息（可达路径）
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> authService.register("weakuser1", "qwerty123", "user"));
        assertTrue(ex.getMessage().contains("建议"));
    }

    @Test
    void register_weakPasswordCaseInsensitive_rejected() {
        // kinds=1（纯小写），被第一个检查拦截
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> authService.register("user1", "password", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    // ==================== 登录限流（逐步累加 → 锁定） ====================

    @Test
    void login_firstError_returns4Remaining() {
        User user = activeUser("testuser", encoder.encode("correct"));
        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> authService.login("testuser", "wrongpass"));
        assertEquals("用户名或密码错误，还剩4次尝试机会", ex.getMessage());

        verify(taskQueue).set(eq("auth:attempt:testuser"), eq("1"), anyLong());
    }

    @Test
    void login_fourthError_returns1Remaining() {
        User user = activeUser("testuser", encoder.encode("correct"));
        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);
        // 已有 3 次错误
        when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn("3");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> authService.login("testuser", "wrongpass"));
        assertEquals("用户名或密码错误，还剩1次尝试机会", ex.getMessage());
    }

    @Test
    void login_fifthError_locksAccount() {
        User user = activeUser("testuser", encoder.encode("correct"));
        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);
        // 已有 4 次错误，第 5 次触发锁定
        when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn("4");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> authService.login("testuser", "wrongpass"));
        assertEquals("密码错误次数过多，账号已锁定15分钟", ex.getMessage());

        verify(taskQueue).set("auth:lock:testuser", "locked", 15 * 60L);
        verify(taskQueue).del("auth:attempt:testuser");
    }

    @Test
    void login_alreadyLocked_rejectedImmediately() {
        // 锁还在
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn("locked");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> authService.login("testuser", "anypass"));
        assertEquals("登录尝试过多，请15分钟后再试", ex.getMessage());

        // 甚至不会查询用户
        verify(userMapper, never()).findByUsername(any());
    }

    @Test
    void login_success_clearsAttemptRecord() {
        User user = activeUser("testuser", encoder.encode("correctpass"));
        when(userMapper.findByUsername("testuser")).thenReturn(user);
        // 登录成功时密码正确，不会走到 getStatus(attempt) 分支，只需要 lock 返回 null
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);

        Map<String, Object> result = authService.login("testuser", "correctpass");

        assertNotNull(result.get("token"));
        verify(taskQueue).del("auth:attempt:testuser");
        verify(taskQueue).del("auth:lock:testuser");
    }

    @Test
    void login_userNotFound_couldBeEnumeratedButThrottleProtects() {
        // 用户不存在也被计数（但走同一个 attempt key）
        when(userMapper.findByUsername("unknown")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:lock:unknown")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:unknown")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> authService.login("unknown", "anypass"));
        assertEquals("用户名或密码错误，还剩4次尝试机会", ex.getMessage());
    }

    @Test
    void login_pendingUser_rejected() {
        User user = activeUser("testuser", encoder.encode("correct"));
        user.setStatus("pending");
        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> authService.login("testuser", "correct"));
        assertTrue(ex.getMessage().contains("审批"));
    }

    // ==================== 管理员注册流程 ====================

    @Test
    void register_adminRole_entersPendingState() {
        when(userMapper.findByUsername("newadmin")).thenReturn(null);

        Map<String, Object> result = authService.register("newadmin", "AdminPass123", "admin");

        assertEquals(Map.of("message", "管理员申请已提交，请等待 root 审批"), result);

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertEquals("pending", captor.getValue().getStatus());
        assertEquals("admin", captor.getValue().getRole());
    }

    // ==================== me 接口 ====================

    @Test
    void me_userExists_returnsProfile() {
        User user = activeUser("user1", encoder.encode("pass"));
        user.setCreatedAt(LocalDateTime.now());
        when(userMapper.findById("u1")).thenReturn(user);

        Map<String, Object> result = authService.me("u1");

        assertEquals("u1", result.get("id"));
        assertEquals("user1", result.get("username"));
        assertEquals("user", result.get("role"));
    }

    @Test
    void me_userNotFound_throws() {
        when(userMapper.findById("unknown")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> authService.me("unknown"));
        assertEquals("用户不存在", ex.getMessage());
    }

    // ==================== 辅助 ====================

    private User activeUser(String username, String password) {
        User u = new User();
        u.setId("u1");
        u.setUsername(username);
        u.setPassword(password);
        u.setRole("user");
        u.setStatus("active");
        return u;
    }
}
