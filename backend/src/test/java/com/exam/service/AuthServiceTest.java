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

    @Test
    void loginWhenLockedShortCircuitsWithoutQueryingUser() {
        when(taskQueue.getStatus("", "auth:lock:lockeduser")).thenReturn("locked");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("lockeduser", "anything"));
        assertEquals("登录尝试过多，请15分钟后再试", ex.getMessage());
        // Lock short-circuit must not hit the DB or attempt counter.
        verify(userMapper, never()).findByUsername(anyString());
        verify(taskQueue, never()).getStatus(eq(""), eq("auth:attempt:lockeduser"));
    }

    @Test
    void fifthFailedAttemptLocksTheAccount() {
        User user = new User();
        user.setPassword(encoder.encode("correctpass"));

        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);
        // Simulate 4 prior failures already recorded → counter is at 4.
        when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn("4");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("testuser", "wrongpass"));
        assertTrue(ex.getMessage().contains("账号已锁定"),
            "the 5th failed attempt must trigger the lockout message");
        verify(taskQueue).set(eq("auth:lock:testuser"), eq("locked"), eq(15L * 60L));
        verify(taskQueue).del("auth:attempt:testuser");
    }

    @Test
    void fourthFailedAttemptDoesNotLockButStillCounts() {
        User user = new User();
        user.setPassword(encoder.encode("correctpass"));

        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);
        // 3 prior failures → this one becomes the 4th → must NOT lock.
        when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn("3");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("testuser", "wrongpass"));
        assertTrue(ex.getMessage().contains("用户名或密码错误"));
        verify(taskQueue, never()).set(eq("auth:lock:testuser"), anyString(), anyLong());
        verify(taskQueue).set(eq("auth:attempt:testuser"), eq("4"), eq(30L * 60L));
    }

    @Test
    void pendingAdminLoginIsRejected() {
        User user = new User();
        user.setId("u1");
        user.setUsername("newadmin");
        user.setPassword(encoder.encode("correctpass"));
        user.setRole("admin");
        user.setStatus("pending");

        when(userMapper.findByUsername("newadmin")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:newadmin")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("newadmin", "correctpass"));
        assertEquals("你的管理员申请正在审批中，请等待 root 审核", ex.getMessage());
    }

    @Test
    void registerAsAdminPutsAccountInPendingState() {
        when(userMapper.findByUsername("newroot")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("newroot", "StrongPass123!", "admin");
        // Admin requests must NOT receive a token and must NOT auto-activate.
        assertNull(result.get("token"));
        assertEquals("管理员申请已提交，请等待 root 审批", result.get("message"));
    }

    @Test
    void passwordShorterThanEightCharactersIsRejected() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "Aa1!", "user"));
        assertEquals("密码长度至少8位", ex.getMessage());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void passwordWithSingleCharacterClassIsRejected() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        // 8 digits only → 1 class → rejected.
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "12345678", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void passwordWithThreeOfFourCharacterClassesIsAccepted() {
        // lowercase + digit + special = 3 kinds → passes the kinds < 3 warning,
        // and does not match any entry on the weak-password blocklist.
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("newuser", "strong1pass!", "user");
        assertNotNull(result.get("token"));
    }

    @Test
    void passwordWithFourCharacterClassesIsAccepted() {
        // All 4 classes → strongest password validation path.
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("newuser", "Strong1Pass!", "user");
        assertNotNull(result.get("token"));
    }

    @Test
    void passwordWithTwoClassesGetsClassSuggestionWarning() {
        // 2 kinds triggers the "建议密码包含...至少3种" branch.
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "password123", "user"));
        assertEquals("建议密码包含大写字母、小写字母、数字和特殊字符中的至少3种", ex.getMessage());
    }
}
