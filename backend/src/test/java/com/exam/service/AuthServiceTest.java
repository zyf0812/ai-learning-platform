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

    // ========== 密码强度校验 ==========

    @Test
    void registerPasswordTooShortThrows() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "Ab1!", "user"));
        assertEquals("密码长度至少8位", ex.getMessage());
    }

    @Test
    void registerPasswordOnlyOneKindThrows() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "abcdefgh", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void registerPasswordTwoKindsWarns() {
        // 只有2种字符时抛出建议提示
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "abcdefg1", "user"));
        assertTrue(ex.getMessage().contains("建议密码包含大写字母、小写字母、数字和特殊字符中的至少3种"));
    }

    @Test
    void registerWeakPasswordThrows() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "12345678", "user"));
        assertEquals("密码过于简单，请换一个", ex.getMessage());
    }

    @Test
    void registerWeakPasswordCaseInsensitiveThrows() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "Password", "user"));
        assertEquals("密码过于简单，请换一个", ex.getMessage());
    }

    @Test
    void registerStrongPasswordSucceeds() {
        // 至少3种字符（大写+小写+数字），非弱密码
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("newuser", "Hello123", "user");

        assertNotNull(result.get("token"));
        verify(userMapper).insert(any(User.class));
    }

    @Test
    void registerPasswordWithSpecialCharSucceeds() {
        // 4种字符全部包含
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("newuser", "Hello@123", "user");

        assertNotNull(result.get("token"));
    }

    // ========== 管理员注册审批 ==========

    @Test
    void registerAdminReturnsPendingMessage() {
        when(userMapper.findByUsername("adminuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("adminuser", "Admin@123", "admin");

        assertNull(result.get("token"));
        assertEquals("管理员申请已提交，请等待 root 审批", result.get("message"));
    }

    @Test
    void registerAdminStatusIsPending() {
        when(userMapper.findByUsername("adminuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        authService.register("adminuser", "Admin@123", "admin");

        verify(userMapper).insert(argThat(user ->
            "pending".equals(user.getStatus()) && "admin".equals(user.getRole())
        ));
    }

    // ========== 登录限流 ==========

    @Test
    void loginLockedAccountThrows() {
        when(taskQueue.getStatus("", "auth:lock:lockeduser")).thenReturn("locked");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("lockeduser", "anypass"));
        assertEquals("登录尝试过多，请15分钟后再试", ex.getMessage());
    }

    @Test
    void loginWrongPasswordIncrementsAttempts() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));

        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("testuser", "wrong"));
        assertTrue(ex.getMessage().contains("还剩4次尝试机会"));

        verify(taskQueue).set(eq("auth:attempt:testuser"), eq("1"), eq(30L * 60));
    }

    @Test
    void loginFourthWrongPasswordIncrementsToFour() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));

        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn("3");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("testuser", "wrong"));
        assertTrue(ex.getMessage().contains("还剩1次尝试机会"));

        verify(taskQueue).set(eq("auth:attempt:testuser"), eq("4"), eq(30L * 60));
    }

    @Test
    void loginFifthWrongPasswordLocksAccount() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));

        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn("4");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("testuser", "wrong"));
        assertEquals("密码错误次数过多，账号已锁定15分钟", ex.getMessage());

        verify(taskQueue).set(eq("auth:lock:testuser"), eq("locked"), eq(15L * 60));
        verify(taskQueue).del("auth:attempt:testuser");
    }

    @Test
    void loginSuccessClearsAttemptAndLock() {
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
        verify(taskQueue).del("auth:lock:testuser");
    }

    // ========== Pending 状态用户登录 ==========

    @Test
    void loginPendingAdminThrows() {
        User user = new User();
        user.setId("u1");
        user.setUsername("pendingadmin");
        user.setPassword(encoder.encode("Admin@123"));
        user.setRole("admin");
        user.setStatus("pending");

        when(userMapper.findByUsername("pendingadmin")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:pendingadmin")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("pendingadmin", "Admin@123"));
        assertEquals("你的管理员申请正在审批中，请等待 root 审核", ex.getMessage());
    }

    // ========== me() 方法 ==========

    @Test
    void meReturnsUserInfo() {
        User user = new User();
        user.setId("u1");
        user.setUsername("testuser");
        user.setRole("user");
        user.setCreatedAt(java.time.LocalDateTime.now());

        when(userMapper.findById("u1")).thenReturn(user);

        var result = authService.me("u1");

        assertEquals("u1", result.get("id"));
        assertEquals("testuser", result.get("username"));
        assertEquals("user", result.get("role"));
        assertNotNull(result.get("createdAt"));
    }

    @Test
    void meNotFoundThrows() {
        when(userMapper.findById("nonexistent")).thenReturn(null);

        assertThrows(RuntimeException.class, () -> authService.me("nonexistent"));
    }
}
