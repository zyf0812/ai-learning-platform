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
import static org.mockito.ArgumentMatchers.eq;
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

        var result = authService.register("newuser", "Password123!", "user");

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
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "Ab1!", "user"));
        assertTrue(ex.getMessage().contains("至少8位"));
    }

    @Test
    void registerPasswordOnlyDigitsThrows() {
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "12345678", "user"));
        assertTrue(ex.getMessage().contains("字母和数字"));
    }

    @Test
    void registerPasswordOnlyTwoKindsWarns() {
        when(userMapper.findByUsername("u1")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "abcdefgh1", "user"));
        assertTrue(ex.getMessage().contains("至少3种"));
    }

    @Test
    void registerPasswordThreeKindsSucceeds() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("u1", "Abcdefg1", "user");
        assertNotNull(result.get("token"));
    }

    @Test
    void registerPasswordUpperAndSpecialOnly_TwoKindsWarns() {
        // 只有大写字母和特殊字符 = 2种字符，kinds<3
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "ABCDEFG!", "user"));
        assertTrue(ex.getMessage().contains("至少3种"));
    }

    // ========== 角色与审批 ==========

    @Test
    void registerAdminReturnsPendingMessage() {
        when(userMapper.findByUsername("newadmin")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("newadmin", "Admin@123", "admin");
        assertNull(result.get("token"));
        assertNotNull(result.get("message"));
        assertTrue(((String) result.get("message")).contains("审批"));
    }

    @Test
    void loginPendingAdminThrows() {
        User user = new User();
        user.setId("u1");
        user.setUsername("pendingAdmin");
        user.setPassword(encoder.encode("Admin@123"));
        user.setRole("admin");
        user.setStatus("pending");

        when(userMapper.findByUsername("pendingAdmin")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:pendingAdmin")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("pendingAdmin", "Admin@123"));
        assertTrue(ex.getMessage().contains("审批中"));
    }

    // ========== 登录限流 ==========

    @Test
    void loginLockedUserThrowsImmediately() {
        when(taskQueue.getStatus("", "auth:lock:lockeduser")).thenReturn("locked");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("lockeduser", "anypass"));
        assertTrue(ex.getMessage().contains("15分钟后再试"));
    }

    @Test
    void loginFifthFailureLocksAccount() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));

        when(userMapper.findByUsername("rateuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:rateuser")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:rateuser")).thenReturn("4");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("rateuser", "wrongpass"));
        assertTrue(ex.getMessage().contains("已锁定15分钟"));
        verify(taskQueue).set(eq("auth:lock:rateuser"), eq("locked"), eq(15L * 60));
        verify(taskQueue).del("auth:attempt:rateuser");
    }

    @Test
    void loginFirstFailureIncrementsAttemptCounter() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));

        when(userMapper.findByUsername("rateuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:rateuser")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:rateuser")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("rateuser", "wrongpass"));
        assertTrue(ex.getMessage().contains("还剩4次"));
        verify(taskQueue).set(eq("auth:attempt:rateuser"), eq("1"), eq(30L * 60));
    }

    // ========== me() ==========

    @Test
    void meWithValidUserReturnsInfo() {
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
    }

    @Test
    void meWithMissingUserThrows() {
        when(userMapper.findById("nonexistent")).thenReturn(null);
        assertThrows(RuntimeException.class, () -> authService.me("nonexistent"));
    }
}
