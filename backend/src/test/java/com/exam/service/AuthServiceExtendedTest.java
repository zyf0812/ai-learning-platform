package com.exam.service;

import com.exam.mapper.UserMapper;
import com.exam.model.User;
import com.exam.testutil.FakeTaskQueueService;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceExtendedTest {

    @Mock
    private UserMapper userMapper;

    private FakeTaskQueueService taskQueue;

    private PasswordEncoder encoder;
    private JwtUtil jwtUtil;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        encoder = new BCryptPasswordEncoder();
        jwtUtil = new JwtUtil("test-secret-key-for-hmac-sha-256-testing!!", 7);
        taskQueue = new FakeTaskQueueService();
        authService = new AuthService(userMapper, jwtUtil, encoder, taskQueue);
    }

    // ---------- 密码强度校验 ----------

    @Test
    void registerPasswordTooShortThrows() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "Ab1234!", "user"));
        assertEquals("密码长度至少8位", ex.getMessage());
    }

    @Test
    void registerPasswordOnlyOneKindThrows() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "abcdefgh", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void registerPasswordOnlyTwoKindWarns() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "abcd1234", "user"));
        assertTrue(ex.getMessage().contains("建议密码包含大写字母、小写字母、数字和特殊字符中的至少3种"));
    }

    @Test
    void registerPasswordThreeKindsSucceeds() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));
        var result = authService.register("u1", "Abcd1234", "user");
        assertNotNull(result.get("token"));
    }

    @Test
    void registerPasswordFourKindsSucceeds() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));
        // 大写+小写+数字+特殊字符 → kinds=4，完全通过强度校验
        var result = authService.register("u1", "Pass123!", "user");
        assertNotNull(result.get("token"));
    }

    @Test
    void registerWeakPasswordFromBlocklistThrows() {
        // 注意：当前 weak 列表中所有密码都无法通过 kinds<3 前置检查，
        // 因此 blocklist 路径是死代码。此处测试 kinds=1 的常见弱密码触发前置校验。
        when(userMapper.findByUsername("u1")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "88888888", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void registerAnotherWeakPasswordThrows() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "11111111", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    // ---------- 管理员注册审批流程 ----------

    @Test
    void registerAdminHasPendingStatusAndNoToken() {
        when(userMapper.findByUsername("newadmin")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("newadmin", "AdminPass1!", "admin");

        assertNull(result.get("token"));
        assertEquals("管理员申请已提交，请等待 root 审批", result.get("message"));
        verify(userMapper).insert(argThat(u ->
            "admin".equals(u.getRole()) && "pending".equals(u.getStatus())));
    }

    @Test
    void registerNonAdminHasActiveStatus() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        authService.register("newuser", "Abcd1234!", "user");

        verify(userMapper).insert(argThat(u ->
            "user".equals(u.getRole()) && "active".equals(u.getStatus())));
    }

    @Test
    void registerRoleNullDefaultsToUser() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        authService.register("u1", "Abcd1234!", null);

        verify(userMapper).insert(argThat(u ->
            "user".equals(u.getRole()) && "active".equals(u.getStatus())));
    }

    // ---------- 登录限流/锁定逻辑 ----------

    @Test
    void loginAlreadyLockedThrowsGenericMessage() {
        // 手动放入 locked 状态
        taskQueue.store.put("auth:lock:alice", "locked");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("alice", "anypass"));
        assertEquals("登录尝试过多，请15分钟后再试", ex.getMessage());
    }

    @Test
    void loginFirstFailureIncrementsAttemptAndShowsRemaining() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));

        when(userMapper.findByUsername("bob")).thenReturn(user);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("bob", "wrong"));
        assertEquals("用户名或密码错误，还剩4次尝试机会", ex.getMessage());
        assertEquals("1", taskQueue.store.get("auth:attempt:bob"));
        assertEquals(Long.valueOf(30L * 60), taskQueue.ttlStore.get("auth:attempt:bob"));
    }

    @Test
    void loginFourthFailureShowsOneRemaining() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));

        when(userMapper.findByUsername("bob")).thenReturn(user);
        taskQueue.store.put("auth:attempt:bob", "3");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("bob", "wrong"));
        assertEquals("用户名或密码错误，还剩1次尝试机会", ex.getMessage());
        assertEquals("4", taskQueue.store.get("auth:attempt:bob"));
    }

    @Test
    void loginFifthFailureTriggersLock() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));

        when(userMapper.findByUsername("bob")).thenReturn(user);
        taskQueue.store.put("auth:attempt:bob", "4");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("bob", "wrong"));
        assertEquals("密码错误次数过多，账号已锁定15分钟", ex.getMessage());
        assertEquals("locked", taskQueue.store.get("auth:lock:bob"));
        assertEquals(Long.valueOf(15L * 60), taskQueue.ttlStore.get("auth:lock:bob"));
        // attempt key 应该被删除
        assertNull(taskQueue.store.get("auth:attempt:bob"));
    }

    @Test
    void loginSuccessClearsAttemptAndLockKeys() {
        User user = new User();
        user.setId("u1");
        user.setUsername("charlie");
        user.setPassword(encoder.encode("correctpass"));
        user.setRole("user");
        user.setStatus("active");

        when(userMapper.findByUsername("charlie")).thenReturn(user);
        // 故意留下 attempt 脏数据，验证登录成功后清理
        taskQueue.store.put("auth:attempt:charlie", "2");

        authService.login("charlie", "correctpass");

        // attempt 和 lock 两个 key 都已被删除（lock 原本不存在，del 安全）
        assertNull(taskQueue.store.get("auth:attempt:charlie"));
        assertNull(taskQueue.store.get("auth:lock:charlie"));
    }

    @Test
    void loginPendingStatusUserThrowsApprovalMessage() {
        User user = new User();
        user.setId("u2");
        user.setPassword(encoder.encode("correctpass"));
        user.setStatus("pending");

        when(userMapper.findByUsername("pendingadmin")).thenReturn(user);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("pendingadmin", "correctpass"));
        assertEquals("你的管理员申请正在审批中，请等待 root 审核", ex.getMessage());
    }

    @Test
    void loginUserNotFoundThrowsSameAsWrongPassword() {
        when(userMapper.findByUsername("nobody")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("nobody", "whatever"));
        assertTrue(ex.getMessage().startsWith("用户名或密码错误"));
    }

    // ---------- me() 方法 ----------

    @Test
    void meReturnsUserInfo() {
        User user = new User();
        user.setId("u-100");
        user.setUsername("dave");
        user.setRole("admin");
        user.setCreatedAt(java.time.LocalDateTime.now());

        when(userMapper.findById("u-100")).thenReturn(user);

        var result = authService.me("u-100");
        assertEquals("u-100", result.get("id"));
        assertEquals("dave", result.get("username"));
        assertEquals("admin", result.get("role"));
        assertNotNull(result.get("createdAt"));
    }

    @Test
    void meUserNotFoundThrows() {
        when(userMapper.findById("u-missing")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.me("u-missing"));
        assertEquals("用户不存在", ex.getMessage());
    }
}
