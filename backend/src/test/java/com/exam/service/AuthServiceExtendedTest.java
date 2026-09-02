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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

    // ========== 密码强度校验：checkPasswordStrength ==========

    @Test
    void registerPasswordTooShortThrows() {
        // 长度 < 8
        when(userMapper.findByUsername("u1")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
                () -> authService.register("u1", "Abc12!", "user"));
        assertEquals("密码长度至少8位", ex.getMessage());
    }

    @Test
    void registerPasswordOnlyOneKindThrows() {
        // 全小写字母，仅 1 种字符
        when(userMapper.findByUsername("u2")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
                () -> authService.register("u2", "abcdefgh", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void registerPasswordTwoKindsWarnsButSucceeds() {
        // 小写 + 数字 = 2 种，提示但通过
        when(userMapper.findByUsername("u3")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("u3", "abcd1234", "user");
        assertNotNull(result.get("token"));
        verify(userMapper).insert(any(User.class));
    }

    @Test
    void registerPasswordThreeKindsAllows() {
        // 大写 + 小写 + 数字 = 3 种，满足
        when(userMapper.findByUsername("u4")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("u4", "Abcd1234", "user");
        assertNotNull(result.get("token"));
    }

    @Test
    void registerPasswordFourKindsAllows() {
        // 大写 + 小写 + 数字 + 特殊 = 4 种
        when(userMapper.findByUsername("u5")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("u5", "Abc@1234", "user");
        assertNotNull(result.get("token"));
    }

    @Test
    void registerKnownWeakPasswordRejected() {
        var ex = assertThrows(RuntimeException.class,
                () -> authService.register("u6", "Password1", "user"));
        // 123456789 等弱密码列表：大小写不敏感
        when(userMapper.findByUsername("u6b")).thenReturn(null);
        var ex2 = assertThrows(RuntimeException.class,
                () -> authService.register("u6b", "PASSWORD", "user"));
        assertTrue(ex2.getMessage().contains("过于简单") || ex2.getMessage().contains("长度") || ex2.getMessage().contains("字母和数字"));
        // 经典弱密码 12345678 + 同时满足2种类要求(数字) → 实际1种类
        when(userMapper.findByUsername("u6c")).thenReturn(null);
        var ex3 = assertThrows(RuntimeException.class,
                () -> authService.register("u6c", "12345678", "user"));
        assertTrue(ex3.getMessage().contains("字母和数字") || ex3.getMessage().contains("过于简单"));
    }

    @Test
    void registerCaseInsensitiveWeakPasswordDetected() {
        // "Password" 匹配 password（忽略大小写），但同时要先过种类校验
        // Password1 → 大写+小写+数字(3种) → 通过种类校验后匹配弱密码
        when(userMapper.findByUsername("u7")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
                () -> authService.register("u7", "Password1", "user"));
        assertEquals("密码过于简单，请换一个", ex.getMessage());
    }

    // ========== 管理员注册：pending 状态 ==========

    @Test
    void registerAdminReturnsPendingMessageNoToken() {
        when(userMapper.findByUsername("admin1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("admin1", "Strong@Pass1", "admin");

        // 管理员申请不返回 token，只返回审批消息
        assertNull(result.get("token"));
        assertNotNull(result.get("message"));
        assertTrue(((String) result.get("message")).contains("审批"));

        // 验证写入的用户状态是 pending
        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertEquals("admin", captor.getValue().getRole());
        assertEquals("pending", captor.getValue().getStatus());
    }

    @Test
    void registerNormalUserReturnsTokenAndActive() {
        when(userMapper.findByUsername("norm1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("norm1", "Abcd1234", "user");

        assertNotNull(result.get("token"));
        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertEquals("user", captor.getValue().getRole());
        assertEquals("active", captor.getValue().getStatus());
    }

    // ========== 登录限流与锁定 ==========

    @Test
    void loginAlreadyLockedThrowsLockoutMessage() {
        when(taskQueue.getStatus("", "auth:lock:lockeduser")).thenReturn("locked");

        var ex = assertThrows(RuntimeException.class,
                () -> authService.login("lockeduser", "anypass"));
        assertTrue(ex.getMessage().contains("15分钟"));
        verify(userMapper, never()).findByUsername(anyString());
    }

    @Test
    void loginWrongPasswordIncrementsAttempts() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));
        when(userMapper.findByUsername("baduser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:baduser")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:baduser")).thenReturn(null); // 第1次失败

        var ex = assertThrows(RuntimeException.class,
                () -> authService.login("baduser", "wrong"));
        assertTrue(ex.getMessage().contains("还剩4次"));

        // 尝试次数应被设置为 1（30分钟TTL）
        verify(taskQueue).set(eq("auth:attempt:baduser"), eq("1"), eq(30 * 60L));
    }

    @Test
    void login5thWrongPasswordLocksAccount() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));
        when(userMapper.findByUsername("lockme")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:lockme")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:lockme")).thenReturn("4"); // 已有4次，本次第5次

        var ex = assertThrows(RuntimeException.class,
                () -> authService.login("lockme", "wrong5"));
        assertTrue(ex.getMessage().contains("账号已锁定15分钟"));

        // 验证锁15分钟 + 清除尝试计数
        verify(taskQueue).set(eq("auth:lock:lockme"), eq("locked"), eq(15 * 60L));
        verify(taskQueue).del("auth:attempt:lockme");
    }

    @Test
    void loginWrongPasswordCountsDownAttemptsRemaining() {
        User user = new User();
        user.setPassword(encoder.encode("ok"));
        when(userMapper.findByUsername("trials")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:trials")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:trials")).thenReturn("2"); // 已失败2次

        var ex = assertThrows(RuntimeException.class,
                () -> authService.login("trials", "bad"));
        // count=3 → 剩余 2 次
        assertTrue(ex.getMessage().contains("还剩2次"), "实际消息: " + ex.getMessage());
    }

    @Test
    void loginSuccessClearsAttemptsAndLock() {
        User user = new User();
        user.setId("u99");
        user.setUsername("gooduser");
        user.setPassword(encoder.encode("goodpass"));
        user.setRole("user");
        user.setStatus("active");
        when(userMapper.findByUsername("gooduser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:gooduser")).thenReturn(null);

        var result = authService.login("gooduser", "goodpass");

        assertNotNull(result.get("token"));
        verify(taskQueue).del("auth:attempt:gooduser");
        verify(taskQueue).del("auth:lock:gooduser");
    }

    // ========== 登录：pending 状态阻止 ==========

    @Test
    void loginPendingAdminUserThrowsApprovalMessage() {
        User user = new User();
        user.setId("u100");
        user.setUsername("admin_candidate");
        user.setPassword(encoder.encode("Strong@Pass1"));
        user.setStatus("pending");
        when(userMapper.findByUsername("admin_candidate")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:admin_candidate")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
                () -> authService.login("admin_candidate", "Strong@Pass1"));
        assertTrue(ex.getMessage().contains("审批中") || ex.getMessage().contains("审核"));
    }

    // ========== me() ==========

    @Test
    void meReturnsUserInfo() {
        User user = new User();
        user.setId("u200");
        user.setUsername("me_user");
        user.setRole("user");
        user.setCreatedAt(java.time.LocalDateTime.now());
        when(userMapper.findById("u200")).thenReturn(user);

        Map<String, Object> result = authService.me("u200");
        assertEquals("u200", result.get("id"));
        assertEquals("me_user", result.get("username"));
        assertEquals("user", result.get("role"));
        assertNotNull(result.get("createdAt"));
    }

    @Test
    void meMissingUserThrows() {
        when(userMapper.findById("nouser")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
                () -> authService.me("nouser"));
        assertEquals("用户不存在", ex.getMessage());
    }

    // ========== login 不存在用户（与错误密码同等处理） ==========

    @Test
    void loginNonExistentUserRecordsAttempt() {
        when(userMapper.findByUsername("ghost")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:lock:ghost")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:ghost")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
                () -> authService.login("ghost", "any"));
        assertTrue(ex.getMessage().contains("用户名或密码错误"));
        verify(taskQueue).set(eq("auth:attempt:ghost"), eq("1"), eq(30 * 60L));
    }
}
