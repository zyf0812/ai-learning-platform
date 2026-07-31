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

    // ---------- Password strength validation ----------

    @Test
    void registerShortPasswordRejected() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "ab", "user"));
        assertTrue(ex.getMessage().contains("长度至少8位"));
        verify(userMapper, never()).insert(any());
    }

    @Test
    void registerPasswordOnlyDigitsRejected() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        // Only digits: 1 kind of char < 2 required
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "12345678", "user"));
        assertTrue(ex.getMessage().contains("字母和数字"));
    }

    @Test
    void registerPasswordOnlyLettersRejected() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        // Only letters (lower): 1 kind
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", "abcdefgh", "user"));
        assertTrue(ex.getMessage().contains("字母和数字"));
    }

    @Test
    void registerPasswordTwoKindsPassesBaseCheck() {
        // Lower + digits = 2 kinds, >= 2, >= 3 warning not thrown as hard error?
        // The code: kinds < 2 -> "需包含字母和数字", kinds < 3 -> "建议包含至少3种"
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        // Lower + digit = 2 kinds. Passes kinds < 2 check. kinds < 3 check returns warning msg.
        // But: the register() method throws if checkPasswordStrength() != null, so warnings are also thrown
        // Wait let's re-read: checkPasswordStrength returns non-null for kinds<3 -> "建议..."
        // which is not null, so register() throws. Hmm wait let me verify by looking.
        // Actually the code:
        // String pwError = checkPasswordStrength(password);
        // if (pwError != null) throw new RuntimeException(pwError);
        // So yes, kinds<3 returns a suggestion string (non-null) -> thrown as error
        // So 2 kinds is rejected with "建议..." message
        String pass = "abcd1234";  // lower + digit = 2 kinds
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("newuser", pass, "user"));
        assertTrue(ex.getMessage().contains("至少3种") || ex.getMessage().contains("字母和数字"),
            "Expected strength error, got: " + ex.getMessage());
    }

    @Test
    void registerPasswordThreeKindsSucceeds() {
        // Lower + Upper + digit = 3 kinds, pass
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("newuser", "Abcdefg1", "user");
        assertNotNull(result.get("token"));
    }

    @Test
    void registerPasswordCaseInsensitiveWeakPasswordRejected() {
        // "password" + "1" = "Password1"? No. Test case-insensitive matching on the list.
        // "password" (lowercase-only check against weak list element "password")
        // But "password" is only letters. Let's test case-insensitive: "PASSWORD" matches "password"?
        // Actually weak list is {"12345678", ..., "password"}.
        // password.equalsIgnoreCase("PASSWORD") -> true. So "PASSWORD" should fail.
        // BUT "PASSWORD" has only 1 kind (upper letter) -> fails the kinds<2 check before reaching weak list.
        // Let's use a clear one: 88888888 (all digits) -> fails kinds<2, we can't reach weak check for most.
        // Test via "12345678" -> same, only digits.
        // For clean test of weak detection: need password that passes kinds>=3 check first AND matches list case-insensitively.
        // "PASSWORD" + "1" + "!" = "PASSWORD1!" -> 3 kinds (upper, digit, special) -> kinds>=3 OK.
        // Now weak check: "PASSWORD1!".equalsIgnoreCase("password")? No. length 10 vs 8.
        // Simplify: use a password that passes kinds>=3 AND equalsIgnoreCase the list.
        // "Qwerty123" -> 3 kinds (upper, lower, digit). List has "qwerty123" -> exact ignoreCase match!
        when(userMapper.findByUsername("qwertyuser")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("qwertyuser", "Qwerty123", "user"));
        assertTrue(ex.getMessage().contains("过于简单"),
            "Expected weak password error, got: " + ex.getMessage());
    }

    @Test
    void registerCommonWeakPassword12345678Rejected() {
        when(userMapper.findByUsername("u")).thenReturn(null);
        var ex = assertThrows(RuntimeException.class,
            () -> authService.register("u", "12345678", "user"));
        assertTrue(ex.getMessage().contains("字母和数字") || ex.getMessage().contains("过于简单"));
    }

    @Test
    void registerVeryCommonWeakPasswordsRejected() {
        String[] weak = {"123456789", "qwerty123", "abc123456", "88888888", "11111111"};
        for (String w : weak) {
            // Each must be either weak password error or strength requirement error
            String finalW = w;
            // don't bother mocking userMapper every time; since check is before findByUsername?
            // Actually order: findByUsername -> checkPasswordStrength -> insert. So check comes after
            when(userMapper.findByUsername("u" + finalW)).thenReturn(null);
            var ex = assertThrows(RuntimeException.class,
                () -> authService.register("u" + finalW, finalW, "user"));
            assertNotNull(ex.getMessage());
        }
    }

    // ---------- Admin registration / pending status ----------

    @Test
    void registerAdminRoleSetsPendingStatus() {
        when(userMapper.findByUsername("admincandidate")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("admincandidate", "AdminPass1", "admin");

        // No token -> pending message
        assertNull(result.get("token"));
        assertNotNull(result.get("message"));
        assertTrue(((String) result.get("message")).contains("审批"));
    }

    @Test
    void registerNonAdminRoleGetsActiveStatus() {
        when(userMapper.findByUsername("regular")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("regular", "Regular12", "user");
        assertNotNull(result.get("token"));
        @SuppressWarnings("unchecked")
        var user = (java.util.Map<String, Object>) result.get("user");
        assertEquals("user", user.get("role"));
    }

    @Test
    void registerNullOrInvalidRoleDefaultsToUser() {
        when(userMapper.findByUsername("norole")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        var result = authService.register("norole", "PassWord1!", null);
        @SuppressWarnings("unchecked")
        var user = (java.util.Map<String, Object>) result.get("user");
        assertEquals("user", user.get("role"));
    }

    // ---------- Login rate limiting / lockout ----------

    @Test
    void loginAlreadyLockedThrowsImmediately() {
        when(taskQueue.getStatus("", "auth:lock:lockeduser")).thenReturn("locked");
        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("lockeduser", "anypass"));
        assertTrue(ex.getMessage().contains("15分钟"));
        // Should not even reach userMapper lookup
        verify(userMapper, never()).findByUsername(anyString());
    }

    @Test
    void loginWrongPasswordIncrementsAttempts() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));
        when(userMapper.findByUsername("failuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:failuser")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:failuser")).thenReturn(null);  // first failure

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("failuser", "wrong"));
        assertTrue(ex.getMessage().contains("还剩4次"), "Expected 4 remaining, got: " + ex.getMessage());

        verify(taskQueue).set(eq("auth:attempt:failuser"), eq("1"), eq(30L * 60));
    }

    @Test
    void loginFourPrevFailuresFifthWrongLocksAccount() {
        // 4 pre-existing failures: count becomes 4 + 1 = 5 -> triggers lock path
        User user = new User();
        user.setPassword(encoder.encode("correct"));
        when(userMapper.findByUsername("failuser4")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:failuser4")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:failuser4")).thenReturn("4");

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("failuser4", "wrong"));
        assertTrue(ex.getMessage().contains("锁定15分钟"),
            "5th total failure must lock account. Msg: " + ex.getMessage());
        verify(taskQueue).set(eq("auth:lock:failuser4"), eq("locked"), eq(15L * 60));
        verify(taskQueue).del(eq("auth:attempt:failuser4"));
    }

    @Test
    void loginFifthFailureLocksAccount() {
        User user = new User();
        user.setPassword(encoder.encode("correct"));
        when(userMapper.findByUsername("lockme")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:lockme")).thenReturn(null);
        when(taskQueue.getStatus("", "auth:attempt:lockme")).thenReturn("4");

        var ex = assertThrows(RuntimeException.class, () -> authService.login("lockme", "wrong"));
        assertTrue(ex.getMessage().contains("锁定15分钟"));

        verify(taskQueue).set(eq("auth:lock:lockme"), eq("locked"), eq(15L * 60));
        verify(taskQueue).del(eq("auth:attempt:lockme"));
    }

    @Test
    void loginSuccessClearsAttemptsAndLock() {
        User user = new User();
        user.setId("u1");
        user.setUsername("successuser");
        user.setPassword(encoder.encode("correctpass"));
        user.setRole("user");
        user.setStatus("active");

        when(userMapper.findByUsername("successuser")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:successuser")).thenReturn(null);

        var result = authService.login("successuser", "correctpass");
        assertNotNull(result.get("token"));

        verify(taskQueue).del("auth:attempt:successuser");
        verify(taskQueue).del("auth:lock:successuser");
    }

    // ---------- Pending user login rejection ----------

    @Test
    void loginPendingAdminRejected() {
        User user = new User();
        user.setId("admin1");
        user.setUsername("pendingadmin");
        user.setPassword(encoder.encode("AdminPass1"));
        user.setRole("admin");
        user.setStatus("pending");

        when(userMapper.findByUsername("pendingadmin")).thenReturn(user);
        when(taskQueue.getStatus("", "auth:lock:pendingadmin")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> authService.login("pendingadmin", "AdminPass1"));
        assertTrue(ex.getMessage().contains("审批"));

        // Even on valid cred + pending, attempts should be cleared (the code does clear before check)
        verify(taskQueue).del("auth:attempt:pendingadmin");
    }

    // ---------- me() method ----------

    @Test
    void meReturnsUserInfo() {
        User user = new User();
        user.setId("u123");
        user.setUsername("myuser");
        user.setRole("user");
        user.setCreatedAt(java.time.LocalDateTime.now());

        when(userMapper.findById("u123")).thenReturn(user);

        var result = authService.me("u123");
        assertEquals("u123", result.get("id"));
        assertEquals("myuser", result.get("username"));
        assertEquals("user", result.get("role"));
        assertNotNull(result.get("createdAt"));
    }

    @Test
    void meNonexistentUserThrows() {
        when(userMapper.findById("nouser")).thenReturn(null);
        assertThrows(RuntimeException.class, () -> authService.me("nouser"));
    }
}
