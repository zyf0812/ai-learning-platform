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

        // 使用满足当前 3 种字符强度规则的密码，以真正走通注册成功分支
        var result = authService.register("newuser", "Pass123word", "user");

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

    // ---- 登录限流 / 锁定逻辑（安全关键，原测试仅覆盖 1 次错误密码） ----

    @Test
    void loginRejectsWhenAccountAlreadyLocked() {
        // 锁定键存在时应短路拒绝，且不应再查库校验密码
        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn("locked");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("testuser", "anything"));
        assertEquals("登录尝试过多，请15分钟后再试", ex.getMessage());
        verify(userMapper, never()).findByUsername(anyString());
    }

    @Test
    void loginLocksAccountOnFifthFailedAttempt() {
        User user = new User();
        user.setUsername("testuser");
        user.setPassword(encoder.encode("correctpass"));

        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);
        when(userMapper.findByUsername("testuser")).thenReturn(user);
        // 已失败 4 次，本次为第 5 次，应触发锁定
        when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn("4");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("testuser", "wrongpass"));
        assertEquals("密码错误次数过多，账号已锁定15分钟", ex.getMessage());

        // 应写入锁定键并清空计数键，且不应再走“记录尝试次数”分支
        verify(taskQueue).set(eq("auth:lock:testuser"), eq("locked"), eq(15 * 60L));
        verify(taskQueue).del("auth:attempt:testuser");
        verify(taskQueue, never()).set(eq("auth:attempt:testuser"), anyString(), anyLong());
    }

    @Test
    void loginReportsRemainingAttemptsBeforeLock() {
        User user = new User();
        user.setUsername("testuser");
        user.setPassword(encoder.encode("correctpass"));

        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);
        when(userMapper.findByUsername("testuser")).thenReturn(user);
        // 已失败 1 次 -> 本次第 2 次，应剩 3 次，且不锁定
        when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn("1");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("testuser", "wrongpass"));
        assertEquals("用户名或密码错误，还剩3次尝试机会", ex.getMessage());
        verify(taskQueue).set(eq("auth:attempt:testuser"), eq("2"), eq(30 * 60L));
        verify(taskQueue, never()).set(eq("auth:lock:testuser"), anyString(), anyLong());
    }

    @Test
    void loginRejectsPendingAdminEvenWithCorrectPassword() {
        User user = new User();
        user.setId("u1");
        user.setUsername("testuser");
        user.setPassword(encoder.encode("correctpass"));
        user.setRole("admin");
        user.setStatus("pending");

        when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);
        when(userMapper.findByUsername("testuser")).thenReturn(user);

        // 密码正确，但管理员尚未通过审批 -> 仍应拒绝登录
        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("testuser", "correctpass"));
        assertEquals("你的管理员申请正在审批中，请等待 root 审核", ex.getMessage());
        // 成功校验密码后应清除尝试/锁定记录
        verify(taskQueue).del("auth:attempt:testuser");
        verify(taskQueue).del("auth:lock:testuser");
    }

    // ---- 密码强度校验（数据校验，原测试未覆盖各分支） ----

    @Test
    void registerRejectsTooShortPassword() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "Ab1!", "user"));
        assertEquals("密码长度至少8位", ex.getMessage());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void registerRejectsSingleCharKindPassword() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "abcdefgh", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void registerRejectsTwoCharKindPasswordWithAdvice() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "abcd1234", "user"));
        // 字母+数字共 2 种，不足 3 种，应给出“建议”但仍然拒绝
        assertTrue(ex.getMessage().startsWith("建议密码包含"), ex.getMessage());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void registerRejectsWeakCommonPasswordEvenWithThreeCharKinds() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);

        // 大写+小写+数字共 3 种，通过种类校验，但命中弱密码清单（忽略大小写）
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "Qwerty123", "user"));
        assertEquals("密码过于简单，请换一个", ex.getMessage());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void registerAdminRoleYieldsPendingStatusAndNoToken() {
        when(userMapper.findByUsername("newadmin")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("newadmin", "Abc123!@#", "admin");

        // 管理员申请应返回提示而非 token，且不应误发可登录凭证
        assertEquals("管理员申请已提交，请等待 root 审批", result.get("message"));
        assertFalse(result.containsKey("token"), "管理员待审批状态不应签发 token");
        verify(userMapper).insert(any(User.class));
    }
}
