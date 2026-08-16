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
    void register_passwordTooShort_throws() {
        when(userMapper.findByUsername("u1")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "Ab1$xyz", "user"));

        assertEquals("密码长度至少8位", ex.getMessage());
    }

    @Test
    void register_passwordOnlyLetters_throws() {
        when(userMapper.findByUsername("u1")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "OnlyLetters", "user"));

        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void register_passwordOnlyDigits_throws() {
        when(userMapper.findByUsername("u1")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "12345678", "user"));

        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void register_passwordOnlyTwoKinds_weakWarning() {
        // 只有小写 + 数字 = 2种，应该提示至少3种
        when(userMapper.findByUsername("u1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "lowercase123", "user"));

        assertEquals("建议密码包含大写字母、小写字母、数字和特殊字符中的至少3种", ex.getMessage());
    }

    @Test
    void register_passwordCommonWeak12345678_throws() {
        when(userMapper.findByUsername("u1")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "12345678", "user"));

        assertEquals("密码过于简单，请换一个", ex.getMessage());
    }

    @Test
    void register_passwordCommonWeakPassword_throws() {
        when(userMapper.findByUsername("u1")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "Password123", "user"));

        // password 大小写不敏感匹配
        assertEquals("密码过于简单，请换一个", ex.getMessage());
    }

    @Test
    void register_passwordCommonWeak88888888_throws() {
        when(userMapper.findByUsername("u1")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "88888888", "user"));

        assertEquals("密码过于简单，请换一个", ex.getMessage());
    }

    @Test
    void register_strongPasswordThreeKinds_succeeds() {
        // 大写 + 小写 + 数字 = 3种，满足要求
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("newuser", "StrongPass123", "user");

        assertNotNull(result.get("token"));
        verify(userMapper).insert(any(User.class));
    }

    @Test
    void register_strongPasswordWithSpecial_succeeds() {
        // 大写 + 小写 + 数字 + 特殊 = 4种
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("newuser", "Str0ng!Pass#", "user");

        assertNotNull(result.get("token"));
        verify(userMapper).insert(any(User.class));
    }

    // ==================== 管理员注册审批 ====================

    @Test
    void register_adminRole_setsPendingStatus() {
        when(userMapper.findByUsername("admin1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("admin1", "AdminPass123!", "admin");

        // 管理员注册不返回 token，返回审批消息
        assertNull(result.get("token"));
        assertEquals("管理员申请已提交，请等待 root 审批", result.get("message"));
        verify(userMapper).insert(argThat(u ->
            "admin".equals(u.getRole()) && "pending".equals(u.getStatus())));
    }

    @Test
    void register_userRole_setsActiveStatus() {
        when(userMapper.findByUsername("user1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("user1", "UserPass123!", "user");

        assertNotNull(result.get("token"));
        verify(userMapper).insert(argThat(u ->
            "user".equals(u.getRole()) && "active".equals(u.getStatus())));
    }

    @Test
    void register_nullRole_defaultsToUserActive() {
        when(userMapper.findByUsername("user1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("user1", "UserPass123!", null);

        assertNotNull(result.get("token"));
        verify(userMapper).insert(argThat(u ->
            "user".equals(u.getRole()) && "active".equals(u.getStatus())));
    }

    @Test
    void register_randomRole_treatedAsUser() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("u1", "UserPass123!", "superadmin");

        // 非 "admin" 一律按 user 处理
        assertNotNull(result.get("token"));
        verify(userMapper).insert(argThat(u ->
            "user".equals(u.getRole()) && "active".equals(u.getStatus())));
    }

    // ==================== 登录锁定机制 ====================

    @Test
    void login_alreadyLocked_throwsImmediately() {
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn("locked");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("testuser", "anypass"));

        assertEquals("登录尝试过多，请15分钟后再试", ex.getMessage());
        // 用户查询不应被执行
        verify(userMapper, never()).findByUsername(anyString());
    }

    @Test
    void login_wrongPasswordFirstTime_recordsAttempt() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));

        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("testuser", "wrong"));

        assertTrue(ex.getMessage().contains("还剩4次尝试机会"));
        verify(taskQueue).set(eq("auth:attempt:testuser"), eq("1"), eq(30 * 60L));
    }

    @Test
    void login_wrongPasswordFourthTime_showsOneRemaining() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));

        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn("3");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("testuser", "wrong"));

        assertTrue(ex.getMessage().contains("还剩1次尝试机会"));
        verify(taskQueue).set(eq("auth:attempt:testuser"), eq("4"), eq(30 * 60L));
    }

    @Test
    void login_wrongPasswordFifthTime_locksAccount() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));

        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn("4");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("testuser", "wrong"));

        assertEquals("密码错误次数过多，账号已锁定15分钟", ex.getMessage());
        verify(taskQueue).set(eq("auth:lock:testuser"), eq("locked"), eq(15 * 60L));
        verify(taskQueue).del("auth:attempt:testuser");
    }

    @Test
    void login_wrongPasswordThenSuccess_clearsAttempts() {
        User user = new User();
        user.setId("u1");
        user.setUsername("tuser");
        user.setPassword(encoder.encode("correct"));
        user.setStatus("active");
        user.setRole("user");

        // 先有 2 次失败记录
        when(userMapper.findByUsername("tuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:tuser")).thenReturn(null);

        var result = authService.login("tuser", "correct");

        assertNotNull(result.get("token"));
        verify(taskQueue).del("auth:attempt:tuser");
        verify(taskQueue).del("auth:lock:tuser");
    }

    // ==================== pending 状态用户登录 ====================

    @Test
    void login_pendingAdminUser_throwsApprovalMessage() {
        User user = new User();
        user.setId("u1");
        user.setUsername("pendingadmin");
        user.setPassword(encoder.encode("Correct123!"));
        user.setRole("admin");
        user.setStatus("pending");

        when(userMapper.findByUsername("pendingadmin")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:pendingadmin")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("pendingadmin", "Correct123!"));

        assertEquals("你的管理员申请正在审批中，请等待 root 审核", ex.getMessage());
    }

    // ==================== me() 方法 ====================

    @Test
    void me_existingUser_returnsInfo() {
        User user = new User();
        user.setId("u123");
        user.setUsername("john");
        user.setRole("user");
        user.setCreatedAt(java.time.LocalDateTime.now());

        when(userMapper.findById("u123")).thenReturn(user);

        var result = authService.me("u123");

        assertEquals("u123", result.get("id"));
        assertEquals("john", result.get("username"));
        assertEquals("user", result.get("role"));
        assertNotNull(result.get("createdAt"));
    }

    @Test
    void me_userNotFound_throws() {
        when(userMapper.findById("nonexistent")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.me("nonexistent"));

        assertEquals("用户不存在", ex.getMessage());
    }

    // ==================== 注册返回的 user 信息 ====================

    @Test
    void register_successReturnsCorrectUserMap() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("newuser", "StrongPass123!", "user");

        @SuppressWarnings("unchecked")
        var userMap = (java.util.Map<String, Object>) result.get("user");
        assertEquals("newuser", userMap.get("username"));
        assertEquals("user", userMap.get("role"));
        assertNotNull(userMap.get("id"));
        // id 应为 8 字符 UUID 子串
        assertEquals(8, ((String) userMap.get("id")).length());
    }

    @Test
    void login_successReturnsCorrectUserMap() {
        User user = new User();
        user.setId("abcdefgh");
        user.setUsername("loginuser");
        user.setPassword(encoder.encode("LoginPass1!"));
        user.setRole("admin");
        user.setStatus("active");

        when(userMapper.findByUsername("loginuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:loginuser")).thenReturn(null);

        var result = authService.login("loginuser", "LoginPass1!");

        assertNotNull(result.get("token"));
        @SuppressWarnings("unchecked")
        var userMap = (java.util.Map<String, Object>) result.get("user");
        assertEquals("abcdefgh", userMap.get("id"));
        assertEquals("loginuser", userMap.get("username"));
        assertEquals("admin", userMap.get("role"));
    }

    // ==================== 用户名不存在 ====================

    @Test
    void login_userNotFound_triggersAttemptCounter() {
        when(userMapper.findByUsername("ghost")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:lock:ghost")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:ghost")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("ghost", "anypass"));

        assertTrue(ex.getMessage().contains("用户名或密码错误"));
        // 即使用户名不存在，也记录尝试次数（防用户名枚举）
        verify(taskQueue).set(eq("auth:attempt:ghost"), eq("1"), eq(30 * 60L));
    }
}
