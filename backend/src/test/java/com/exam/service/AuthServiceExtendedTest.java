package com.exam.service;

import com.exam.mapper.UserMapper;
import com.exam.model.User;
import com.exam.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
class AuthServiceExtendedTest {

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

    @Nested
    @DisplayName("Password strength validation tests")
    class PasswordStrengthTests {

        @Test
        @DisplayName("rejects passwords shorter than 8 characters")
        void rejectsTooShortPassword() {
            when(userMapper.findByUsername("user")).thenReturn(null);

            var ex = assertThrows(RuntimeException.class,
                () -> authService.register("user", "1234567", "user"));
            assertEquals("密码长度至少8位", ex.getMessage());
        }

        @Test
        @DisplayName("rejects passwords with insufficient character variety")
        void rejectsInsufficientVariety() {
            when(userMapper.findByUsername("user")).thenReturn(null);

            String[] weakPasswords = {"alllowercase", "ALLUPPERCASE", "1234567890"};
            for (String pw : weakPasswords) {
                var ex = assertThrows(RuntimeException.class,
                    () -> authService.register("user", pw, "user"));
                assertTrue(ex.getMessage().contains("密码需包含字母和数字")
                    || ex.getMessage().contains("至少3种"));
            }
        }

        @Test
        @DisplayName("rejects common weak passwords")
        void rejectsCommonWeakPasswords() {
            when(userMapper.findByUsername("user")).thenReturn(null);

            String[] weakPasswords = {"password", "12345678", "qwerty123"};
            for (String pw : weakPasswords) {
                var ex = assertThrows(RuntimeException.class,
                    () -> authService.register("user", pw, "user"));
                assertEquals("密码过于简单，请换一个", ex.getMessage());
            }
        }

        @Test
        @DisplayName("accepts strong passwords")
        void acceptsStrongPassword() {
            when(userMapper.findByUsername("user")).thenReturn(null);
            doNothing().when(userMapper).insert(any(User.class));

            String[] strongPasswords = {"Str0ng!Pass", "C0mpl3x@Pw", "Aa1#Bb2$"};
            for (String pw : strongPasswords) {
                assertDoesNotThrow(() -> authService.register("user" + pw.hashCode(), pw, "user"));
            }
        }

        @Test
        @DisplayName("password with exactly 2 character kinds passes initial check but gets warning")
        void twoKindsPassButGetWarning() {
            when(userMapper.findByUsername("user")).thenReturn(null);

            String pwWith2Kinds = "abcdefgh1";
            var ex = assertThrows(RuntimeException.class,
                () -> authService.register("user", pwWith2Kinds, "user"));
            assertTrue(ex.getMessage().contains("至少3种"));
        }
    }

    @Nested
    @DisplayName("Admin registration flow tests")
    class AdminRegistrationTests {

        @Test
        @DisplayName("admin registration returns pending status")
        void adminRegistrationPending() {
            when(userMapper.findByUsername("admin")).thenReturn(null);
            doNothing().when(userMapper).insert(any(User.class));

            var result = authService.register("admin", "Str0ng!Pass", "admin");

            assertEquals("管理员申请已提交，请等待 root 审批", result.get("message"));
            assertNull(result.get("token"));
        }

        @Test
        @DisplayName("user registration returns token immediately")
        void userRegistrationReturnsToken() {
            when(userMapper.findByUsername("user")).thenReturn(null);
            doNothing().when(userMapper).insert(any(User.class));

            var result = authService.register("user", "Str0ng!Pass", "user");

            assertNotNull(result.get("token"));
        }

        @Test
        @DisplayName("null role defaults to user")
        void nullRoleDefaultsToUser() {
            when(userMapper.findByUsername("user")).thenReturn(null);
            doNothing().when(userMapper).insert(any(User.class));

            var result = authService.register("user", "Str0ng!Pass", null);

            assertNotNull(result.get("token"));
            assertEquals("user", ((java.util.Map<?, ?>) result.get("user")).get("role"));
        }
    }

    @Nested
    @DisplayName("Login lockout tests")
    class LoginLockoutTests {

        @Test
        @DisplayName("locked account throws lockout error")
        void lockedAccountThrows() {
            when(taskQueue.getStatus("", "auth:lock:lockeduser")).thenReturn("locked");

            var ex = assertThrows(RuntimeException.class,
                () -> authService.login("lockeduser", "anypass"));
            assertTrue(ex.getMessage().contains("15分钟"));
        }

        @Test
        @DisplayName("failed login increments attempt count")
        void failedLoginIncrementsCount() {
            User user = new User();
            user.setId("u1");
            user.setPassword(encoder.encode("correctpass"));
            user.setStatus("active");

            when(userMapper.findByUsername("testuser")).thenReturn(user);
            when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);
            when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn(null);

            authService.login("testuser", "wrongpass");

            verify(taskQueue).set(eq("auth:attempt:testuser"), eq("1"), anyLong());
        }

        @Test
        @DisplayName("fifth failure locks the account")
        void fifthFailureLocksAccount() {
            User user = new User();
            user.setId("u1");
            user.setPassword(encoder.encode("correctpass"));
            user.setStatus("active");

            when(userMapper.findByUsername("testuser")).thenReturn(user);
            when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);
            when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn("4");

            var ex = assertThrows(RuntimeException.class,
                () -> authService.login("testuser", "wrongpass"));
            assertTrue(ex.getMessage().contains("锁定15分钟"));
        }

        @Test
        @DisplayName("login success clears attempt and lock records")
        void successClearsRecords() {
            User user = new User();
            user.setId("u1");
            user.setUsername("testuser");
            user.setPassword(encoder.encode("correctpass"));
            user.setRole("user");
            user.setStatus("active");

            when(userMapper.findByUsername("testuser")).thenReturn(user);
            when(taskQueue.getStatus("", "auth:lock:testuser")).thenReturn(null);
            when(taskQueue.getStatus("", "auth:attempt:testuser")).thenReturn("2");

            authService.login("testuser", "correctpass");

            verify(taskQueue).del("auth:attempt:testuser");
            verify(taskQueue).del("auth:lock:testuser");
        }

        @Test
        @DisplayName("pending admin login throws approval error")
        void pendingAdminThrows() {
            User user = new User();
            user.setId("u1");
            user.setUsername("adminuser");
            user.setPassword(encoder.encode("correctpass"));
            user.setRole("admin");
            user.setStatus("pending");

            when(userMapper.findByUsername("adminuser")).thenReturn(user);
            when(taskQueue.getStatus("", "auth:lock:adminuser")).thenReturn(null);

            var ex = assertThrows(RuntimeException.class,
                () -> authService.login("adminuser", "correctpass"));
            assertTrue(ex.getMessage().contains("审批"));
        }

        @Test
        @DisplayName("nonexistent user throws error")
        void nonexistentUserThrows() {
            when(userMapper.findByUsername("nobody")).thenReturn(null);
            when(taskQueue.getStatus("", "auth:lock:nobody")).thenReturn(null);
            when(taskQueue.getStatus("", "auth:attempt:nobody")).thenReturn(null);

            assertThrows(RuntimeException.class,
                () -> authService.login("nobody", "anypass"));
        }
    }

    @Nested
    @DisplayName("me() tests")
    class MeTests {

        @Test
        @DisplayName("returns user info when found")
        void returnsUserInfo() {
            User user = new User();
            user.setId("u1");
            user.setUsername("testuser");
            user.setRole("user");

            when(userMapper.findById("u1")).thenReturn(user);

            var result = authService.me("u1");

            assertEquals("u1", result.get("id"));
            assertEquals("testuser", result.get("username"));
            assertEquals("user", result.get("role"));
        }

        @Test
        @DisplayName("throws when user not found")
        void throwsWhenNotFound() {
            when(userMapper.findById("unknown")).thenReturn(null);

            assertThrows(RuntimeException.class,
                () -> authService.me("unknown"));
        }
    }
}