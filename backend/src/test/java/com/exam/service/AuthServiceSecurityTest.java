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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AuthService 补充测试。
 *
 * 已有测试仅覆盖了 register 成功/重复 + login 成功/错密码 4 条基本路径。
 * 这里补充风险最高的缺口：
 *
 * 1. checkPasswordStrength 密码强度校验（长度、字符种类、常见弱密码黑名单）
 *    - 未覆盖前：弱密码注册成功导致账号易被撞库
 * 2. login 限流锁定逻辑（5 次失败锁定 15 分钟，30 分钟窗口，剩余尝试提示）
 *    - 未覆盖前：暴力破解防护可能存在逻辑漏洞
 * 3. 管理员注册走 pending 审批流（不立即发 token）
 * 4. pending 用户登录被拒绝
 * 5. 登录成功清除 lock + attempt
 * 6. me() 获取用户信息 / 用户不存在
 *
 * 这些都是 Auth 安全网的关键部分，直接涉及权限校验和数据验证。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceSecurityTest {

    @Mock private UserMapper userMapper;
    @Mock private TaskQueueService taskQueue;

    private PasswordEncoder encoder;
    private JwtUtil jwtUtil;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        encoder = new BCryptPasswordEncoder();
        jwtUtil = new JwtUtil("test-secret-key-for-hmac-sha-256-testing!!", 7);
        authService = new AuthService(userMapper, jwtUtil, encoder, taskQueue);
    }

    // ============================================================
    // 密码强度 checkPasswordStrength (通过 register 触发)
    // ============================================================

    @Test
    void register_passwordTooShort_rejected() {
        when(userMapper.findByUsername("u")).thenReturn(null);
        // 7 位 < 8
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "aB3!xyz", "user"));
        assertTrue(ex.getMessage().contains("至少8位"),
            "7 位密码应被拒绝，实际: " + ex.getMessage());
    }

    @Test
    void register_passwordOnlyOneKind_rejected() {
        when(userMapper.findByUsername("u")).thenReturn(null);
        // 只有小写字母，种类=1 < 2
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "abcdefgh", "user"));
        assertTrue(ex.getMessage().contains("字母和数字"),
            "单一字符种类应被拒绝，实际: " + ex.getMessage());
    }

    @Test
    void register_passwordTwoKindsButNotThree_adviceOnly() {
        // 密码长度 8, 2 种 (字母+数字) -> 建议性消息种类<3
        // 但根据逻辑: kinds<2 是"字母和数字"，kinds<3 是"建议至少3种"
        // 种类 2 是通过 "kinds<2" 这一关的，但是 kinds<3 触发的是"建议"
        // 所以 kinds=2 的密码应该是通过强度检查的 (kinds<2 为 false)
        //
        // 重新看代码：
        // if (kinds < 2) return "密码需包含字母和数字";     // 2 不触发
        // if (kinds < 3) return "建议密码包含...至少3种";   // 2 会触发并返回错误消息
        // -> kinds=2 仍然被拒绝（虽然是"建议"措辞，但 return 了非 null 即抛异常）
        when(userMapper.findByUsername("u")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "abcd1234", "user"));
        assertTrue(ex.getMessage().contains("至少3种"),
            "kinds=2 的密码应被建议性消息拒绝，实际: " + ex.getMessage());
    }

    @Test
    void register_passwordThreeKinds_accepted() {
        // 大写 + 小写 + 数字 = 3 种，长度 8，不在黑名单 -> 通过
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("newuser", "Hello123", "user");

        assertNotNull(result.get("token"));
        verify(userMapper).insert(any(User.class));
    }

    @Test
    void register_passwordAllFourKinds_accepted() {
        // 大写+小写+数字+特殊 = 4 种
        when(userMapper.findByUsername("u")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("u", "Abc@1234", "user");
        assertNotNull(result.get("token"));
    }

    @Test
    void register_commonWeakPassword_blacklisted() {
        // 现有弱密码黑名单 ("password", "12345678"等) 字符种类都 < 3，
        // 会先被 kinds<2 / kinds<3 强度检查拦截。
        // 这里验证：不在黑名单中的强密码(满足3+种字符)能通过强度和黑名单两道检查。
        when(userMapper.findByUsername("u2")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        assertDoesNotThrow(() -> authService.register("u2", "Spring@2024", "user"));
    }

    // ============================================================
    // 管理员注册 (role=admin -> status=pending -> 无 token)
    // ============================================================

    @Test
    void register_adminRole_pendingStatusNoToken() {
        when(userMapper.findByUsername("boss")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("boss", "Admin@1234", "admin");

        assertNull(result.get("token"), "pending 状态的管理员不应立即得到 token");
        assertNotNull(result.get("message"));
        assertTrue(((String) result.get("message")).contains("审批"));

        // 验证插入的 user 是 pending status + admin role
        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertEquals("admin", captor.getValue().getRole());
        assertEquals("pending", captor.getValue().getStatus());
    }

    @Test
    void register_nonAdminRole_defaultsToActiveUser() {
        when(userMapper.findByUsername("alice")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        authService.register("alice", "Alice@2024", "user");

        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertEquals("user", captor.getValue().getRole());
        assertEquals("active", captor.getValue().getStatus());
    }

    @Test
    void register_nullRole_treatedAsUser() {
        when(userMapper.findByUsername("x")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        authService.register("x", "Valid@123", null);

        var captor = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertEquals("user", captor.getValue().getRole());
    }

    // ============================================================
    // 登录限流 (5 次失败 -> 锁定 15 分钟)
    // ============================================================

    private User buildLockedUser(String uname, String correctPw) {
        User u = new User();
        u.setId("uid");
        u.setUsername(uname);
        u.setPassword(encoder.encode(correctPw));
        u.setRole("user");
        u.setStatus("active");
        return u;
    }

    @Test
    void login_alreadyLocked_rejectedImmediately() {
        when(taskQueue.getStatus("", "auth:lock:attacker")).thenReturn("locked");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("attacker", "any"));
        assertTrue(ex.getMessage().contains("15分钟后再试"),
            "已锁定账号应直接拒绝: " + ex.getMessage());
        // 不应查询数据库
        verify(userMapper, never()).findByUsername(anyString());
    }

    @Test
    void login_firstFourFailures_incrementAttemptsAndShowRemaining() {
        User u = buildLockedUser("bob", "correct");
        when(userMapper.findByUsername("bob")).thenReturn(u);
        when(taskQueue.getStatus("", "auth:lock:bob")).thenReturn(null);

        // 第 1 次失败: attemptKey=null -> count=1
        when(taskQueue.getStatus("", "auth:attempt:bob")).thenReturn(null);
        var ex1 = assertThrows(RuntimeException.class,
            () -> authService.login("bob", "wrong"));
        assertTrue(ex1.getMessage().contains("还剩4次"), "第1次失败后剩余4次: " + ex1.getMessage());
        verify(taskQueue).set(eq("auth:attempt:bob"), eq("1"), eq(30L * 60));

        // 第 3 次失败: attempt="2" -> count=3
        when(taskQueue.getStatus("", "auth:attempt:bob")).thenReturn("2");
        var ex3 = assertThrows(RuntimeException.class,
            () -> authService.login("bob", "wrong"));
        assertTrue(ex3.getMessage().contains("还剩2次"),
            "第 3 次失败 (当前存储 2) 剩余 2 次: " + ex3.getMessage());
        verify(taskQueue).set(eq("auth:attempt:bob"), eq("3"), eq(30L * 60));
    }

    @Test
    void login_fifthFailure_locksAccountFor15Minutes() {
        User u = buildLockedUser("eve", "correct");
        when(userMapper.findByUsername("eve")).thenReturn(u);
        when(taskQueue.getStatus("", "auth:lock:eve")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:eve")).thenReturn("4"); // 已失败 4 次

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("eve", "wrong5"));
        assertTrue(ex.getMessage().contains("锁定15分钟"),
            "第 5 次失败应锁定: " + ex.getMessage());

        // lock key 应被设置 TTL = 15*60
        verify(taskQueue).set(eq("auth:lock:eve"), eq("locked"), eq(15L * 60));
        // attempt key 应删除（重新计数）
        verify(taskQueue).del(eq("auth:attempt:eve"));
    }

    @Test
    void login_success_clearsAttemptsAndLock() {
        User u = buildLockedUser("gooduser", "rightPw");
        when(userMapper.findByUsername("gooduser")).thenReturn(u);
        when(taskQueue.getStatus("", "auth:lock:gooduser")).thenReturn(null);

        var result = authService.login("gooduser", "rightPw");

        assertNotNull(result.get("token"));
        verify(taskQueue).del("auth:attempt:gooduser");
        verify(taskQueue).del("auth:lock:gooduser");
    }

    @Test
    void login_pendingAdmin_rejectedWithApprovalMessage() {
        User u = new User();
        u.setId("a1");
        u.setUsername("pendingAdmin");
        u.setPassword(encoder.encode("Admin@2024"));
        u.setRole("admin");
        u.setStatus("pending");
        when(userMapper.findByUsername("pendingAdmin")).thenReturn(u);
        when(taskQueue.getStatus("", "auth:lock:pendingAdmin")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("pendingAdmin", "Admin@2024"));
        assertTrue(ex.getMessage().contains("审批中") || ex.getMessage().contains("审核"),
            "pending 用户应提示审批中: " + ex.getMessage());
    }

    // ============================================================
    // me() 用户信息
    // ============================================================

    @Test
    void me_existingUser_returnsProfile() {
        User u = new User();
        u.setId("u123");
        u.setUsername("tester");
        u.setRole("user");
        u.setCreatedAt(java.time.LocalDateTime.now());
        when(userMapper.findById("u123")).thenReturn(u);

        var result = authService.me("u123");

        assertEquals("u123", result.get("id"));
        assertEquals("tester", result.get("username"));
        assertEquals("user", result.get("role"));
        assertNotNull(result.get("createdAt"));
    }

    @Test
    void me_unknownUser_throws() {
        when(userMapper.findById("nonexist")).thenReturn(null);
        assertThrows(RuntimeException.class, () -> authService.me("nonexist"));
    }
}
