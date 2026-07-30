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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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

        var result = authService.register("newuser", "Pass123!", "user");

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

    // ===== 密码强度校验分支（此前完全未覆盖） =====

    @Test
    void registerShortPasswordThrows() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "Ab1!", "user"));
        assertEquals("密码长度至少8位", ex.getMessage());
        verify(userMapper, never()).insert(any());
    }

    @Test
    void registerPasswordWithOnlyOneCharKindThrows() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "abcdefgh", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
        verify(userMapper, never()).insert(any());
    }

    @Test
    void registerPasswordWithTwoCharKindsThrows() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "abcdef12", "user"));
        assertEquals("建议密码包含大写字母、小写字母、数字和特殊字符中的至少3种", ex.getMessage());
        verify(userMapper, never()).insert(any());
    }

    @Test
    void registerStrongPasswordSucceeds() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("newuser", "StrongPass123!", "user");

        assertNotNull(result.get("token"));
        verify(userMapper).insert(argThat(u ->
            "user".equals(u.getRole()) && "active".equals(u.getStatus())));
    }

    // ===== 管理员角色注册（pending 审批流，此前未覆盖） =====

    @Test
    void registerAdminRoleSetsPendingStatusAndReturnsNoToken() {
        when(userMapper.findByUsername("adminuser")).thenReturn(null);

        var result = authService.register("adminuser", "StrongPass123!", "admin");

        assertEquals("管理员申请已提交，请等待 root 审批", result.get("message"));
        assertNull(result.get("token"));
        verify(userMapper).insert(argThat(u ->
            "admin".equals(u.getRole()) && "pending".equals(u.getStatus())));
    }

    // ===== 登录限流 / 锁定（安全关键，此前只测了单次错误密码） =====

    @Test
    void loginWhenAlreadyLockedThrowsWithoutLookup() {
        when(taskQueue.getStatus("", "auth:lock:lockeduser")).thenReturn("locked");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("lockeduser", "anypass"));
        assertEquals("登录尝试过多，请15分钟后再试", ex.getMessage());
        // 锁定状态下不应再查库
        verify(userMapper, never()).findByUsername(anyString());
    }

    @Test
    void loginFifthFailedAttemptLocksAccount() {
        User user = new User();
        user.setUsername("fiveduser");
        user.setPassword(encoder.encode("correctpass"));

        when(userMapper.findByUsername("fiveduser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:fiveduser")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:fiveduser")).thenReturn("4");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("fiveduser", "wrongpass"));
        assertEquals("密码错误次数过多，账号已锁定15分钟", ex.getMessage());
        // 第 5 次失败应触发锁定并清除尝试计数
        verify(taskQueue).set("auth:lock:fiveduser", "locked", 15 * 60);
        verify(taskQueue).del("auth:attempt:fiveduser");
    }

    @Test
    void loginFourthFailedAttemptDoesNotLockAndShowsRemaining() {
        User user = new User();
        user.setUsername("fourthuser");
        user.setPassword(encoder.encode("correctpass"));

        when(userMapper.findByUsername("fourthuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:fourthuser")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:fourthuser")).thenReturn("3");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("fourthuser", "wrongpass"));
        assertEquals("用户名或密码错误，还剩1次尝试机会", ex.getMessage());
        // 未达 5 次，仅累计计数，不锁定
        verify(taskQueue).set("auth:attempt:fourthuser", "4", 30 * 60);
        verify(taskQueue, never()).set(eq("auth:lock:fourthuser"), anyString(), anyLong());
    }

    @Test
    void loginWithPendingAdminThrows() {
        User user = new User();
        user.setId("u1");
        user.setUsername("pendingadmin");
        user.setPassword(encoder.encode("correctpass"));
        user.setStatus("pending");

        when(userMapper.findByUsername("pendingadmin")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:pendingadmin")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("pendingadmin", "correctpass"));
        assertEquals("你的管理员申请正在审批中，请等待 root 审核", ex.getMessage());
    }

    // ===== me 方法（此前未覆盖） =====

    @Test
    void meReturnsUserInfo() {
        User user = new User();
        user.setId("u1");
        user.setUsername("testuser");
        user.setRole("user");
        user.setCreatedAt(LocalDateTime.now());
        when(userMapper.findById("u1")).thenReturn(user);

        var result = authService.me("u1");
        assertEquals("u1", result.get("id"));
        assertEquals("testuser", result.get("username"));
        assertEquals("user", result.get("role"));
    }

    @Test
    void meThrowsWhenUserNotFound() {
        when(userMapper.findById("missing")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.me("missing"));
        assertEquals("用户不存在", ex.getMessage());
    }
}
