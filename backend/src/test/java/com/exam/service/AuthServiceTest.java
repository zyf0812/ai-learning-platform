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

        var result = authService.register("newuser", "StrongPass1!", "user");

        assertNotNull(result.get("token"));
        assertEquals("user", ((java.util.Map<?, ?>) result.get("user")).get("role"));
        verify(userMapper).insert(any(User.class));
    }

    @Test
    void registerDuplicateUsernameThrows() {
        when(userMapper.findByUsername("existing")).thenReturn(new User());

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("existing", "StrongPass1!", "user"));
        assertEquals("用户名已存在", ex.getMessage());
    }

    // ===== 密码强度校验（安全关键，原有无覆盖）=====

    @Test
    void registerShortPasswordRejected() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "Ab1!", "user"));
        assertEquals("密码长度至少8位", ex.getMessage());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void registerSingleCharKindPasswordRejected() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        // 仅小写字母 → 字符种类=1
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "abcdefgh", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void registerTwoCharKindPasswordRejected() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        // 小写+数字 → 种类=2，未达 3 种建议
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "password123", "user"));
        assertEquals("建议密码包含大写字母、小写字母、数字和特殊字符中的至少3种", ex.getMessage());
    }

    @Test
    void registerCommonWeakPasswordRejected() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        // 满足强度但命中常见弱密码清单
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "Abc123456", "user"));
        assertEquals("密码过于简单，请换一个", ex.getMessage());
    }

    @Test
    void registerAdminRoleReturnsPendingWithoutToken() {
        when(userMapper.findByUsername("admin1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("admin1", "StrongPass1!", "admin");

        assertEquals("管理员申请已提交，请等待 root 审批", result.get("message"));
        assertNull(result.get("token"));
        // 验证落库状态为 pending
        org.mockito.ArgumentCaptor<User> captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertEquals("admin", captor.getValue().getRole());
        assertEquals("pending", captor.getValue().getStatus());
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

    // ===== 登录限流与账号锁定（安全关键，原有无覆盖）=====

    @Test
    void loginWrongPasswordReportsRemainingAttempts() {
        User user = new User();
        user.setPassword(encoder.encode("correctpass"));

        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn("2"); // 已失败2次

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("testuser", "wrongpass"));
        // 第3次失败，剩余 5-3=2 次
        assertEquals("用户名或密码错误，还剩2次尝试机会", ex.getMessage());
        // 未到5次：仅刷新尝试计数，不锁定
        verify(taskQueue).set("auth:attempt:testuser", "3", 30 * 60);
        verify(taskQueue, never()).set(eq("auth:lock:testuser"), anyString(), anyLong());
    }

    @Test
    void loginFifthFailureLocksAccountAndClearsAttemptCounter() {
        User user = new User();
        user.setPassword(encoder.encode("correctpass"));

        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn("4"); // 已失败4次

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("testuser", "wrongpass"));
        assertEquals("密码错误次数过多，账号已锁定15分钟", ex.getMessage());
        // 第5次：写锁定 key（15分钟 TTL）并清除尝试计数
        verify(taskQueue).set("auth:lock:testuser", "locked", 15 * 60);
        verify(taskQueue).del("auth:attempt:testuser");
    }

    @Test
    void loginLockedAccountRejectedEvenWithCorrectPassword() {
        // 锁定 key 存在即直接拒绝，无需查库
        when(taskQueue.getStatus("", "auth:lock:lockeduser")).thenReturn("locked");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("lockeduser", "correctpass"));
        assertEquals("登录尝试过多，请15分钟后再试", ex.getMessage());
        // 锁定状态下不应再校验密码或读写尝试计数
        verify(userMapper, never()).findByUsername(anyString());
        verify(taskQueue, never()).del(anyString());
        verify(taskQueue, never()).set(anyString(), anyString(), anyLong());
    }

    @Test
    void loginPendingAdminThrowsEvenWithCorrectPassword() {
        User user = new User();
        user.setId("u1");
        user.setUsername("admin1");
        user.setPassword(encoder.encode("correctpass"));
        user.setStatus("pending");

        when(userMapper.findByUsername("admin1")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:admin1")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("admin1", "correctpass"));
        assertEquals("你的管理员申请正在审批中，请等待 root 审核", ex.getMessage());
    }

    @Test
    void loginSuccessClearsBothAttemptAndLockKeys() {
        User user = new User();
        user.setId("u1");
        user.setUsername("testuser");
        user.setPassword(encoder.encode("correctpass"));
        user.setRole("user");
        user.setStatus("active");

        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);

        authService.login("testuser", "correctpass");

        // 成功登录应清除尝试记录与锁定记录
        verify(taskQueue).del("auth:attempt:testuser");
        verify(taskQueue).del("auth:lock:testuser");
    }
}
