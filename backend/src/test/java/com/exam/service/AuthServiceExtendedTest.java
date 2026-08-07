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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceExtendedTest {

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

    // ══════════════════════════════════════════════════════════════════
    // register(): password strength (drives checkPasswordStrength)
    // ══════════════════════════════════════════════════════════════════

    @Test
    void registerPasswordTooShortThrows() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        // Only 7 chars → min 8 required
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "Ab1!xyz", "user"));
        assertTrue(ex.getMessage().contains("至少8位"),
            "Expected min length message, got: " + ex.getMessage());
        // Insert must NOT be called
        verify(userMapper, never()).insert(any());
    }

    @Test
    void registerPasswordAllDigitsFailsTwoKindRule() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        // Only digits (1 kind) → need at least 2 kinds
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "12345678", "user"));
        assertTrue(ex.getMessage().contains("字母和数字"),
            "Expected letter+digit message, got: " + ex.getMessage());
        verify(userMapper, never()).insert(any());
    }

    @Test
    void registerPasswordUpperLowerOnlyTwoKindsFailsThreeKindRule() {
        // Only Upper + Lower case letters = 2 kinds, no digit, no special → kinds < 3 → rejected
        when(userMapper.findByUsername("u1")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "HelloWorld", "user"));
        assertTrue(ex.getMessage().contains("至少3种"),
            "Expected '3 kinds minimum' message, got: " + ex.getMessage());
        verify(userMapper, never()).insert(any());
    }

    @Test
    void registerPasswordUpperDigitOnlyTwoKindsFailsThreeKindRule() {
        // HELLO1234 → all uppercase + digit = 2 kinds (no lower, no special)
        // → kinds < 3 → rejected with 3-kind recommendation
        when(userMapper.findByUsername("u1")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "HELLO1234", "user"));
        assertTrue(ex.getMessage().contains("至少3种"),
            "Upper+Digit should require 3 kinds, got: " + ex.getMessage());
        verify(userMapper, never()).insert(any());
    }

    @Test
    void registerPasswordSpecialDigitOnlyTwoKindsFailsThreeKindRule() {
        // "@@@@1234" → special + digit = 2 kinds (no letters at all → first check: kinds<2? No. kinds=2)
        // Wait check: kinds < 2 requires "字母和数字" (letter + digit). Here we have digit + special.
        // No letters. → kinds<2 check does (hasUpper||hasLower)? No. So kinds=2: digit + special.
        // Wait kinds counts hasUpper=0, hasLower=0, hasDigit=1, hasSpecial=1 → kinds=2.
        // → kinds < 2 check passes (we have 2, not < 2) → next kinds < 3 check fails.
        when(userMapper.findByUsername("u1")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "@@@@1234", "user"));
        assertTrue(ex.getMessage().contains("至少3种"),
            "Expected 3 kinds message, got: " + ex.getMessage());
    }

    @Test
    void registerWeakPasswordExactMatchCaseInsensitive() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        // Qwerty123 → Upper + lower + digit = 3 kinds. Passes kinds checks.
        // weak list has "qwerty123" → equalsIgnoreCase matches "Qwerty123"
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u1", "Qwerty123", "user"));
        assertTrue(ex.getMessage().contains("过于简单"),
            "Expected weak-password message, got: " + ex.getMessage());
        verify(userMapper, never()).insert(any());
    }

    @Test
    void registerStrongPasswordSucceeds() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));
        // Upper + lower + digit + special = 4 kinds, not in weak list
        var res = authService.register("u1", "Strong@Pass99", "user");
        assertNotNull(res.get("token"));
        verify(userMapper).insert(any());
    }

    @Test
    void registerPasswordThreeKindsPasses() {
        when(userMapper.findByUsername("u")).thenReturn(null);
        doNothing().when(userMapper).insert(any());
        // Upper + lower + digit = 3 kinds (no special). Not weak.
        var res = authService.register("u", "ABcd1234", "user");
        assertNotNull(res.get("token"));
    }

    // ══════════════════════════════════════════════════════════════════
    // register(): admin role → pending status
    // ══════════════════════════════════════════════════════════════════

    @Test
    void registerAdminRoleGetsPendingStatusNotToken() {
        when(userMapper.findByUsername("newadmin")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var res = authService.register("newadmin", "Strong@Pass99", "admin");

        // No token issued for admin; returns pending-approval message
        assertNull(res.get("token"),
            "pending admin should NOT receive a token");
        assertNotNull(res.get("message"));
        assertTrue(((String) res.get("message")).contains("审批"));
        verify(userMapper).insert(argThat(u ->
            "admin".equals(u.getRole()) && "pending".equals(u.getStatus())
        ));
    }

    @Test
    void registerRoleNullDefaultsToUserActive() {
        when(userMapper.findByUsername("u1")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var res = authService.register("u1", "Strong@Pass99", null);
        assertNotNull(res.get("token"));
        verify(userMapper).insert(argThat(u ->
            "user".equals(u.getRole()) && "active".equals(u.getStatus())
        ));
    }

    @Test
    void registerAdminSpellingOtherTreatedAsUser() {
        // role != "admin" (e.g. "Admin" capitalized or other strings) → user/active
        when(userMapper.findByUsername("u1")).thenReturn(null);
        doNothing().when(userMapper).insert(any());

        authService.register("u1", "Strong@Pass99", "Admin");
        verify(userMapper).insert(argThat(u ->
            "user".equals(u.getRole()) && "active".equals(u.getStatus())
        ));
    }

    // ══════════════════════════════════════════════════════════════════
    // login(): brute force lockout (rate-limiting)
    // ══════════════════════════════════════════════════════════════════

    private User activeUser(String username, String rawPassword) {
        User u = new User();
        u.setId("uid");
        u.setUsername(username);
        u.setPassword(encoder.encode(rawPassword));
        u.setRole("user");
        u.setStatus("active");
        return u;
    }

    @Test
    void loginLockedOutThrowsBeforeCheckingPassword() {
        // Lock key exists → short-circuit, don't even check password
        when(taskQueue.getStatus("", "auth:lock:hacker")).thenReturn("locked");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("hacker", "any-pass"));
        assertTrue(ex.getMessage().contains("15分钟"),
            "Expected lockout message, got: " + ex.getMessage());
        // userMapper.findByUsername must NOT be called (early short-circuit)
        verify(userMapper, never()).findByUsername(any());
    }

    @Test
    void loginFourthWrongAttemptReportsOneTryLeft() {
        // 4th wrong attempt: count becomes 4 → remaining = 1
        User u = activeUser("victim", "correct");
        when(userMapper.findByUsername("victim")).thenReturn(u);
        when(taskQueue.getStatus("", "auth:lock:victim")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:victim")).thenReturn("3"); // 3 prior failures

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("victim", "WRONG"));
        assertTrue(ex.getMessage().contains("还剩1次"),
            "Expected '1 attempt left' message, got: " + ex.getMessage());
        // New attempt count = 4; should be stored with 30-min window
        verify(taskQueue).set(eq("auth:attempt:victim"), eq("4"), eq(30 * 60));
    }

    @Test
    void loginFifthWrongAttemptLocks15Minutes() {
        // 5th failure → lock key set, attempt key deleted, lockout message
        User u = activeUser("victim", "correct");
        when(userMapper.findByUsername("victim")).thenReturn(u);
        when(taskQueue.getStatus("", "auth:lock:victim")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:victim")).thenReturn("4");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("victim", "WRONG"));
        assertTrue(ex.getMessage().contains("锁定15分钟"));
        verify(taskQueue).set(eq("auth:lock:victim"), eq("locked"), eq(15 * 60));
        verify(taskQueue).del("auth:attempt:victim");
    }

    @Test
    void loginFirstWrongAttemptSetsCountTo1Remaining4() {
        User u = activeUser("victim", "correct");
        when(userMapper.findByUsername("victim")).thenReturn(u);
        when(taskQueue.getStatus("", "auth:lock:victim")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:victim")).thenReturn(null); // no prior attempts

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("victim", "WRONG"));
        assertTrue(ex.getMessage().contains("还剩4次"));
        verify(taskQueue).set(eq("auth:attempt:victim"), eq("1"), eq(30 * 60));
    }

    @Test
    void loginWrongUserNonExistentStillCountsAttempt() {
        // Non-existent user: encoder.matches called with null? code: user==null → fails branch
        // So the attempt counter branch still runs because userMapper returns null
        // Wait: `if (user == null || !encoder.matches(...))` → both paths lead to attempt count
        when(userMapper.findByUsername("nobody")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:lock:nobody")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:nobody")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("nobody", "whatever"));
        assertTrue(ex.getMessage().contains("用户名或密码错误"));
        verify(taskQueue).set(eq("auth:attempt:nobody"), eq("1"), eq(30 * 60));
    }

    // ══════════════════════════════════════════════════════════════════
    // login(): pending status check & success cleanup
    // ══════════════════════════════════════════════════════════════════

    @Test
    void loginPendingAdminStatusThrowsApprovalMessage() {
        User u = activeUser("adminwait", "correct");
        u.setStatus("pending");
        u.setRole("admin");
        when(userMapper.findByUsername("adminwait")).thenReturn(u);
        when(taskQueue.getStatus("", "auth:lock:adminwait")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("adminwait", "correct"));
        assertTrue(ex.getMessage().contains("审批"),
            "Expected pending approval message, got: " + ex.getMessage());
    }

    @Test
    void loginSuccessClearsAttemptAndLockKeys() {
        User u = activeUser("gooduser", "correct");
        when(userMapper.findByUsername("gooduser")).thenReturn(u);
        when(taskQueue.getStatus("", "auth:lock:gooduser")).thenReturn(null);

        var res = authService.login("gooduser", "correct");
        assertNotNull(res.get("token"));
        verify(taskQueue).del("auth:attempt:gooduser");
        verify(taskQueue).del("auth:lock:gooduser");
    }

    // ══════════════════════════════════════════════════════════════════
    // me()
    // ══════════════════════════════════════════════════════════════════

    @Test
    void meReturnsUserInfo() {
        User u = new User();
        u.setId("me1");
        u.setUsername("myname");
        u.setRole("user");
        u.setCreatedAt(java.time.LocalDateTime.now());
        when(userMapper.findById("me1")).thenReturn(u);

        var res = authService.me("me1");
        assertEquals("me1", res.get("id"));
        assertEquals("myname", res.get("username"));
        assertEquals("user", res.get("role"));
        assertNotNull(res.get("createdAt"));
    }

    @Test
    void meUnknownUserThrows() {
        when(userMapper.findById("ghost")).thenReturn(null);
        assertThrows(RuntimeException.class, () -> authService.me("ghost"));
    }
}
