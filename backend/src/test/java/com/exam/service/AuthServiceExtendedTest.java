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

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
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

    // ==================== checkPasswordStrength (via reflection) ====================

    private String invokeCheckPw(String pw) throws Exception {
        Method m = AuthService.class.getDeclaredMethod("checkPasswordStrength", String.class);
        m.setAccessible(true);
        return (String) m.invoke(authService, pw);
    }

    @Test
    void checkPassword_tooShortRejected() throws Exception {
        assertEquals("密码长度至少8位", invokeCheckPw("1234567"));
        assertEquals("密码长度至少8位", invokeCheckPw(""));
    }

    @Test
    void checkPassword_onlyDigitsNeedsLetter() throws Exception {
        // 只有数字，kinds=1 < 2
        assertEquals("密码需包含字母和数字", invokeCheckPw("12345678"));
        // 只有字母，kinds=1 < 2
        assertEquals("密码需包含字母和数字", invokeCheckPw("abcdefgh"));
    }

    @Test
    void checkPassword_twoKindsSuggestsStronger() throws Exception {
        // 小写字母 + 数字 = 2种，< 3
        assertEquals("建议密码包含大写字母、小写字母、数字和特殊字符中的至少3种",
            invokeCheckPw("abc12345"));
        // 大写字母 + 数字 = 2种
        assertEquals("建议密码包含大写字母、小写字母、数字和特殊字符中的至少3种",
            invokeCheckPw("ABC12345"));
        // 大小写字母 = 2种（没有数字或特殊）
        assertEquals("密码需包含字母和数字",
            invokeCheckPw("ABCDEFGa"));
    }

    @Test
    void checkPassword_threeOrMoreKindsPasses() throws Exception {
        // 小写 + 大写 + 数字 = 3种
        assertNull(invokeCheckPw("Abcdefg1"));
        // 小写 + 数字 + 特殊 = 3种
        assertNull(invokeCheckPw("abcdefg1!"));
        // 全部4种
        assertNull(invokeCheckPw("Abcdefg1!"));
    }

    @Test
    void checkPassword_commonWeakPasswordsRejected() throws Exception {
        // 虽然 12345678 只有数字也会被前一条捕获，但我们确认弱密码列表生效
        assertEquals("密码过于简单，请换一个", invokeCheckPw("Password1"));
        assertEquals("密码过于简单，请换一个", invokeCheckPw("Qwerty123"));
        assertEquals("密码过于简单，请换一个", invokeCheckPw("Abc123456"));
        assertEquals("密码过于简单，请换一个", invokeCheckPw("88888888aA"));
        assertEquals("密码过于简单，请换一个", invokeCheckPw("11111111aA!"));
    }

    // ==================== 登录限流 / 锁定 ====================

    @Test
    void login_alreadyLockedThrowsImmediately() {
        when(taskQueue.getStatus("", "auth:lock:alice")).thenReturn("locked");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("alice", "anything"));
        assertTrue(ex.getMessage().contains("15分钟"));
        // 不应该查询用户或递增尝试数
        verify(userMapper, never()).findByUsername(anyString());
    }

    @Test
    void login_failedAttemptsIncrementsAndReturnsRemaining() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));
        when(userMapper.findByUsername("bob")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:bob")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:bob")).thenReturn("2"); // 已经错了2次

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("bob", "wrong"));
        assertTrue(ex.getMessage().contains("还剩2次"), "应提示剩余2次（3+1错=4次使用，剩1？不对：2+1=3次错，剩5-3=2次）");

        verify(taskQueue).set(eq("auth:attempt:bob"), eq("3"), eq(30L * 60));
        verify(taskQueue, never()).set(contains("lock"), anyString(), anyLong());
    }

    @Test
    void login_fifthFailureLocksAccount() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));
        when(userMapper.findByUsername("eve")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:eve")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:eve")).thenReturn("4"); // 已经4次，这是第5次

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("eve", "wrong"));
        assertTrue(ex.getMessage().contains("锁定15分钟"));

        // 设置锁 15 分钟，并清除 attempt 计数
        verify(taskQueue).set(eq("auth:lock:eve"), eq("locked"), eq(15L * 60));
        verify(taskQueue).del("auth:attempt:eve");
    }

    @Test
    void login_firstFailureStartsCounting() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));
        when(userMapper.findByUsername("newbie")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:newbie")).thenReturn(null);
        // 首次：attempt key 不存在，返回 null
        when(taskQueue.getStatus("", "auth:attempt:newbie")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("newbie", "wrong"));
        assertTrue(ex.getMessage().contains("还剩4次"));

        verify(taskQueue).set(eq("auth:attempt:newbie"), eq("1"), eq(30L * 60));
    }

    @Test
    void login_successClearsAttemptAndLock() {
        User user = new User();
        user.setId("u1");
        user.setUsername("good");
        user.setPassword(encoder.encode("correct"));
        user.setRole("user");
        user.setStatus("active");
        when(userMapper.findByUsername("good")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:good")).thenReturn(null);

        var result = authService.login("good", "correct");

        assertNotNull(result.get("token"));
        verify(taskQueue).del("auth:attempt:good");
        verify(taskQueue).del("auth:lock:good");
    }

    // ==================== 管理员注册审批 ====================

    @Test
    void register_adminRoleGoesToPendingApproval() {
        when(userMapper.findByUsername("future-admin")).thenReturn(null);

        var result = authService.register("future-admin", "StrongPw1!", "admin");

        assertEquals("管理员申请已提交，请等待 root 审批",
            result.get("message"));
        // 没有返回 token
        assertNull(result.get("token"));
        verify(userMapper).insert(argThat(u ->
            "admin".equals(u.getRole()) && "pending".equals(u.getStatus())
        ));
    }

    @Test
    void register_userRoleActivatesImmediately() {
        when(userMapper.findByUsername("normal")).thenReturn(null);

        var result = authService.register("normal", "StrongPw1!", "user");

        assertNotNull(result.get("token"));
        verify(userMapper).insert(argThat(u ->
            "user".equals(u.getRole()) && "active".equals(u.getStatus())
        ));
    }

    @Test
    void register_nullRoleDefaultsToUser() {
        when(userMapper.findByUsername("anon")).thenReturn(null);

        var result = authService.register("anon", "StrongPw1!", null);

        assertNotNull(result.get("token"));
        verify(userMapper).insert(argThat(u ->
            "user".equals(u.getRole()) && "active".equals(u.getStatus())
        ));
    }

    // ==================== pending 状态登录拦截 ====================

    @Test
    void login_pendingAdminRejected() {
        User user = new User();
        user.setId("u-pending");
        user.setUsername("admin-wannabe");
        user.setPassword(encoder.encode("StrongPw1!"));
        user.setRole("admin");
        user.setStatus("pending");
        when(userMapper.findByUsername("admin-wannabe")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:admin-wannabe")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("admin-wannabe", "StrongPw1!"));
        assertTrue(ex.getMessage().contains("审批中") || ex.getMessage().contains("审核"));
    }

    // ==================== me() ====================

    @Test
    void me_existingUserReturnsProfile() {
        User user = new User();
        user.setId("u-123");
        user.setUsername("me");
        user.setRole("user");
        user.setCreatedAt(java.time.LocalDateTime.now());
        when(userMapper.findById("u-123")).thenReturn(user);

        var result = authService.me("u-123");
        assertEquals("u-123", result.get("id"));
        assertEquals("me", result.get("username"));
        assertEquals("user", result.get("role"));
        assertNotNull(result.get("createdAt"));
    }

    @Test
    void me_missingUserThrows() {
        when(userMapper.findById("no-such")).thenReturn(null);
        assertThrows(RuntimeException.class, () -> authService.me("no-such"));
    }
}
