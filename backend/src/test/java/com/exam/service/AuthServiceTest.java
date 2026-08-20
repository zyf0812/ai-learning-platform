package com.exam.service;

import com.exam.mapper.UserMapper;
import com.exam.model.User;
import com.exam.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    // Lightweight in-memory fake replacing Mockito.mock(TaskQueueService.class),
    // which fails on JDK 25 due to inline mock maker instrumentation limitations.
    static class FakeTaskQueueService extends TaskQueueService {
        final Map<String, String> store = new HashMap<>();
        final Map<String, Long> ttls = new HashMap<>();
        final List<String> delCalls = new ArrayList<>();
        final List<Object[]> setCalls = new ArrayList<>();

        FakeTaskQueueService() { super(null); }

        @Override public String getStatus(String prefix, String taskId) {
            return store.get(taskId);
        }
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

    @Mock
    private UserMapper userMapper;

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

    @Test
    void registerNewUserReturnsToken() {
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        doNothing().when(userMapper).insert(any(User.class));

        // Password1 = P(upper) + assword(lower) + 1(digit) = 3 kinds, not on weak list
        var result = authService.register("newuser", "Password1", "user");

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

        var result = authService.login("testuser", "correctpass");

        assertNotNull(result.get("token"));
        assertTrue(taskQueue.delCalledWith("auth:attempt:testuser"));
    }

    @Test
    void loginWithWrongPasswordThrows() {
        User user = new User();
        user.setPassword(encoder.encode("correctpass"));

        when(userMapper.findByUsername("testuser")).thenReturn(user);

        assertThrows(RuntimeException.class,
            () -> authService.login("testuser", "wrongpass"));
    }
}