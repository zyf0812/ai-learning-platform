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

import java.util.Map;

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

        var result = authService.register("newuser", "Password123", "user");

        assertNotNull(result.get("token"));
        assertEquals("user", ((Map<?, ?>) result.get("user")).get("role"));
        verify(userMapper).insert(any(User.class));
    }

    @Test
    void registerDuplicateUsernameThrows() {
        when(userMapper.findByUsername("existing")).thenReturn(new User());

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("existing", "pass", "user"));
        assertEquals("用户名已存在", ex.getMessage());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void registerRejectsTooShortPassword() {
        when(userMapper.findByUsername("short")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("short", "Pw123", "user"));

        assertEquals("密码长度至少8位", ex.getMessage());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void registerRejectsPasswordWithSingleCharacterKind() {
        when(userMapper.findByUsername("letters")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("letters", "abcdefgh", "user"));

        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void registerRejectsPasswordWithOnlyTwoCharacterKinds() {
        when(userMapper.findByUsername("twokinds")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("twokinds", "password1", "user"));

        assertEquals("建议密码包含大写字母、小写字母、数字和特殊字符中的至少3种", ex.getMessage());
    }

    @Test
    void registerAdminRequiresApprovalAndReturnsNoToken() {
        when(userMapper.findByUsername("admin1")).thenReturn(null);

        var result = authService.register("admin1", "Password123", "admin");

        assertFalse(result.containsKey("token"));
        assertEquals("管理员申请已提交，请等待 root 审批", result.get("message"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertEquals("admin", captor.getValue().getRole());
        assertEquals("pending", captor.getValue().getStatus());
    }

    @Test
    void loginWithValidCredentialsReturnsTokenAndClearsAttemptState() {
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
    void loginFailureIncrementsAttemptCounterWithRemainingHint() {
        User user = new User();
        user.setPassword(encoder.encode("correctpass"));

        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn("2");

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> authService.login("testuser", "wrongpass"));

        assertEquals("用户名或密码错误，还剩2次尝试机会", ex.getMessage());
        verify(taskQueue).set("auth:attempt:testuser", "3", 1800L);
    }

    @Test
    void loginLocksAccountAfterFifthFailure() {
        User user = new User();
        user.setPassword(encoder.encode("correctpass"));

        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn("4");

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> authService.login("testuser", "wrongpass"));

        assertEquals("密码错误次数过多，账号已锁定15分钟", ex.getMessage());
        verify(taskQueue).set("auth:lock:testuser", "locked", 900L);
        verify(taskQueue).del("auth:attempt:testuser");
    }

    @Test
    void loginRejectsImmediatelyWhenLocked() {
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn("locked");

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> authService.login("testuser", "correctpass"));

        assertEquals("登录尝试过多，请15分钟后再试", ex.getMessage());
        verify(userMapper, never()).findByUsername(anyString());
    }

    @Test
    void loginPendingAdminIsRejectedAfterSuccessfulPasswordCheck() {
        User user = new User();
        user.setPassword(encoder.encode("correctpass"));
        user.setStatus("pending");

        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> authService.login("testuser", "correctpass"));

        assertEquals("你的管理员申请正在审批中，请等待 root 审核", ex.getMessage());
        verify(taskQueue).del("auth:attempt:testuser");
        verify(taskQueue).del("auth:lock:testuser");
    }

    @Test
    void meUnknownUserThrows() {
        when(userMapper.findById("missing")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> authService.me("missing"));

        assertEquals("用户不存在", ex.getMessage());
    }
}
