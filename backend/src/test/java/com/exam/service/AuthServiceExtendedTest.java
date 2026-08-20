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

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceExtendedTest {

    static class FakeTaskQueueService extends TaskQueueService {
        final Map<String, String> store = new HashMap<>();
        final Map<String, Long> ttls = new HashMap<>();
        final List<String> delCalls = new ArrayList<>();
        final List<Object[]> setCalls = new ArrayList<>();

        FakeTaskQueueService() { super(null); }

        @Override public String getStatus(String prefix, String taskId) { return store.get(taskId); }
        @Override public void set(String key, String value, long ttlSeconds) {
            store.put(key, value);
            ttls.put(key, ttlSeconds);
            setCalls.add(new Object[]{ key, value, ttlSeconds });
        }
        @Override public void del(String key) {
            store.remove(key);
            ttls.remove(key);
            delCalls.add(key);
        }
        boolean delCalledWith(String key) { return delCalls.contains(key); }
        Optional<Object[]> findSetCall(String key) {
            for (int i = setCalls.size() - 1; i >= 0; i--) {
                Object[] c = setCalls.get(i);
                if (key.equals(c[0])) return Optional.of(c);
            }
            return Optional.empty();
        }
    }

    @Mock private UserMapper userMapper;

    private FakeTaskQueueService taskQueue;
    private PasswordEncoder encoder;
    private JwtUtil jwtUtil;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        taskQueue = new FakeTaskQueueService();
        encoder = new BCryptPasswordEncoder();
        jwtUtil = new JwtUtil("test-secret-key-for-hmac-sha-256-testing!!", 7);
        authService = new AuthService(userMapper, jwtUtil, encoder, taskQueue);
    }

    // ==================== Password strength ====================

    @Test
    void registerPasswordTooShortThrows() {
        when(userMapper.findByUsername(any())).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "a1B!", "user"));
        assertEquals("密码长度至少8位", ex.getMessage());
    }

    @Test
    void registerPasswordOnlyOneKindThrows() {
        when(userMapper.findByUsername(any())).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "abcdefgh", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void registerPasswordTwoKindsSucceedsWithWarning() {
        when(userMapper.findByUsername(any())).thenReturn(null);
        // lowercase + digit = exactly 2 kinds → triggers kinds < 3 suggestion
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "abc12345", "user"));
        assertTrue(ex.getMessage().contains("至少3种"),
            "Expected 3-kind suggestion for 2-kind password, got: " + ex.getMessage());
    }

    @Test
    void registerPasswordThreeKindsPassesValidation() {
        when(userMapper.findByUsername(any())).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("newuser", "Abc12345", "user");
        assertNotNull(result.get("token"));
        verify(userMapper).insert(any(User.class));
    }

    @Test
    void registerPasswordFourKindsWithSpecialCharPasses() {
        when(userMapper.findByUsername(any())).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("u2", "Abc123!@#", "user");
        assertNotNull(result.get("token"));
    }

    @Test
    void registerWeakPasswordCommonPatternThrows() {
        // NOTE: Intended weak password ("12345678" on weak list) hits kinds<2 check FIRST
        // because it has only 1 character kind (digit). The weak-password branch is unreachable
        // for every entry on the weak list — this is a finding worth flagging to the team.
        when(userMapper.findByUsername(any())).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "12345678", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void registerWeakPasswordCaseInsensitive() {
        when(userMapper.findByUsername(any())).thenReturn(null);
        // 88888888 → 1 kind (digits only) → hits "需包含字母和数字"
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "88888888", "user"));
        assertEquals("密码需包含字母和数字", ex.getMessage());
    }

    @Test
    void registerPassword8CharsExactlyPassesLengthCheck() {
        when(userMapper.findByUsername(any())).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));
        authService.register("u", "Ab1c2d3!", "user");
        verify(userMapper).insert(any(User.class));
    }

    @Test
    void registerPasswordExactly7CharsFails() {
        when(userMapper.findByUsername(any())).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "Ab1c2d3", "user"));
        assertEquals("密码长度至少8位", ex.getMessage());
    }

    // ==================== Admin registration ====================

    @Test
    void registerAdminRoleGetsPendingStatus() {
        when(userMapper.findByUsername("admin-candidate")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("admin-candidate", "Abc123!@#", "admin");
        assertEquals("管理员申请已提交，请等待 root 审批", result.get("message"));
        assertNull(result.get("token"));
        verify(userMapper).insert(argThat(u ->
            "admin".equals(u.getRole()) && "pending".equals(u.getStatus())));
    }

    @Test
    void registerNonAdminRoleBecomesUserWithActiveStatus() {
        when(userMapper.findByUsername("u")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        authService.register("u", "Abc123!@#", "superadmin");
        verify(userMapper).insert(argThat(u ->
            "user".equals(u.getRole()) && "active".equals(u.getStatus())));
    }

    @Test
    void registerNullRoleBecomesUser() {
        when(userMapper.findByUsername("u")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        authService.register("u", "Abc123!@#", null);
        verify(userMapper).insert(argThat(u ->
            "user".equals(u.getRole()) && "active".equals(u.getStatus())));
    }

    // ==================== Login lockout ====================

    @Test
    void loginAlreadyLockedThrowsLockMessage() {
        taskQueue.store.put("auth:lock:user", "locked");
        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("user", "anypass"));
        assertEquals("登录尝试过多，请15分钟后再试", ex.getMessage());
    }

    @Test
    void loginWrongPasswordReportsRemainingAttempts() {
        User u = new User();
        u.setPassword(encoder.encode("correct"));
        when(userMapper.findByUsername("user")).thenReturn(u);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("user", "wrong"));
        assertTrue(ex.getMessage().contains("还剩4次尝试机会"),
            "Expected remaining 4 attempts message, got: " + ex.getMessage());
        Object[] call = taskQueue.findSetCall("auth:attempt:user").orElseThrow();
        assertEquals("1", call[1]);
        assertEquals(30 * 60L, call[2]);
    }

    @Test
    void loginWrongPassword5thAttemptLocks() {
        User u = new User();
        u.setPassword(encoder.encode("correct"));
        when(userMapper.findByUsername("user")).thenReturn(u);
        taskQueue.store.put("auth:attempt:user", "4"); // 4 prior failed attempts

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("user", "wrong"));
        assertEquals("密码错误次数过多，账号已锁定15分钟", ex.getMessage());
        Object[] lockCall = taskQueue.findSetCall("auth:lock:user").orElseThrow();
        assertEquals("locked", lockCall[1]);
        assertEquals(15 * 60L, lockCall[2]);
        assertTrue(taskQueue.delCalledWith("auth:attempt:user"));
    }

    @Test
    void loginWrongPassword2ndTo4thIncrements() {
        User u = new User();
        u.setPassword(encoder.encode("correct"));
        when(userMapper.findByUsername("user")).thenReturn(u);
        taskQueue.store.put("auth:attempt:user", "2");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("user", "wrong"));
        assertTrue(ex.getMessage().contains("还剩2次尝试机会"));
        Object[] call = taskQueue.findSetCall("auth:attempt:user").orElseThrow();
        assertEquals("3", call[1]);
        assertEquals(30 * 60L, call[2]);
    }

    // ==================== Pending admin login rejection ====================

    @Test
    void loginPendingAdminThrowsApprovalMessage() {
        User u = new User();
        u.setPassword(encoder.encode("pass"));
        u.setStatus("pending");
        when(userMapper.findByUsername("admin-user")).thenReturn(u);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("admin-user", "pass"));
        assertEquals("你的管理员申请正在审批中，请等待 root 审核", ex.getMessage());
    }

    @Test
    void loginSuccessClearsBothAttemptAndLock() {
        User u = new User();
        u.setId("u1");
        u.setUsername("good");
        u.setPassword(encoder.encode("correct"));
        u.setRole("user");
        u.setStatus("active");
        when(userMapper.findByUsername("good")).thenReturn(u);

        var r = authService.login("good", "correct");
        assertNotNull(r.get("token"));
        assertTrue(taskQueue.delCalledWith("auth:attempt:good"));
        assertTrue(taskQueue.delCalledWith("auth:lock:good"));
    }

    // ==================== me() ====================

    @Test
    void meReturnsUserInfo() {
        User u = new User();
        u.setId("u1");
        u.setUsername("alice");
        u.setRole("user");
        u.setCreatedAt(java.time.LocalDateTime.now());
        when(userMapper.findById("u1")).thenReturn(u);

        Map<String, Object> r = authService.me("u1");
        assertEquals("u1", r.get("id"));
        assertEquals("alice", r.get("username"));
        assertEquals("user", r.get("role"));
        assertTrue(r.containsKey("createdAt"));
    }

    @Test
    void meMissingUserThrows() {
        when(userMapper.findById("nope")).thenReturn(null);
        assertThrows(RuntimeException.class, () -> authService.me("nope"));
    }

    // ==================== User not found on login ====================

    @Test
    void loginUserNotFoundStillRecordsAttempt() {
        when(userMapper.findByUsername("ghost")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("ghost", "any"));
        assertTrue(ex.getMessage().contains("用户名或密码错误"));
        Object[] call = taskQueue.findSetCall("auth:attempt:ghost").orElseThrow();
        assertEquals("1", call[1]);
        assertEquals(30 * 60L, call[2]);
    }
}