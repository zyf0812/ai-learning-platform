package com.exam.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskQueueServiceTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private TaskQueueService taskQueue;

    @BeforeEach
    void setUp() {
        // redis.opsForValue() 返回 mock valueOps
        when(redis.opsForValue()).thenReturn(valueOps);
        taskQueue = new TaskQueueService(redis);
    }

    // ==================== getStatus ====================

    @Test
    void getStatusReturnsRedisValue() {
        when(valueOps.get("prefix:task123")).thenReturn("DONE:result789");
        String status = taskQueue.getStatus("prefix:", "task123");
        assertEquals("DONE:result789", status);
    }

    @Test
    void getStatusMissingReturnsNull() {
        when(valueOps.get("prefix:nonexist")).thenReturn(null);
        assertNull(taskQueue.getStatus("prefix:", "nonexist"));
    }

    @Test
    void getStatusEmptyPrefixWorks() {
        // AuthService 用空前缀访问 auth:lock:xxx
        when(valueOps.get("auth:lock:userA")).thenReturn("locked");
        assertEquals("locked", taskQueue.getStatus("", "auth:lock:userA"));
    }

    // ==================== set ====================

    @Test
    void setWithTtlCallsRedisSetWithDuration() {
        ArgumentCaptor<Duration> captor = ArgumentCaptor.forClass(Duration.class);
        taskQueue.set("k", "v", 300);
        verify(valueOps).set(eq("k"), eq("v"), captor.capture());
        assertEquals(300, captor.getValue().getSeconds());
    }

    // ==================== del ====================

    @Test
    void delCallsRedisDelete() {
        taskQueue.del("mykey");
        verify(redis).delete("mykey");
    }

    // ==================== newTaskId ====================

    @Test
    void newTaskIdLength8() {
        String id = taskQueue.newTaskId();
        assertNotNull(id);
        assertEquals(8, id.length());
    }

    @Test
    void newTaskIdMultipleCallsUnique() {
        String id1 = taskQueue.newTaskId();
        String id2 = taskQueue.newTaskId();
        String id3 = taskQueue.newTaskId();
        assertNotEquals(id1, id2);
        assertNotEquals(id2, id3);
        assertNotEquals(id1, id3);
    }

    // ==================== submit: 同步验证状态流转（不走 @Async） ====================
    // 注意：单元测试中 @Async 不会生效（无Spring上下文），因此 submit 同步执行，
    // 可直接断言 RUNNING → DONE/FAILED 的 Redis 调用。

    @Test
    void submitSuccessfulTaskWritesRunningThenDone() {
        AtomicReference<String> lastKey = new AtomicReference<>();
        AtomicReference<String> lastValue = new AtomicReference<>();

        doAnswer(inv -> {
            lastKey.set(inv.getArgument(0));
            lastValue.set(inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString());

        taskQueue.submit("tid42", () -> "OK_RESULT", "jobs:");

        // 验证至少两次 set：RUNNING 然后 DONE:OK_RESULT
        verify(valueOps, times(2)).set(anyString(), anyString());
        // 最终写入应是 DONE: 前缀
        assertEquals("jobs:tid42", lastKey.get());
        assertEquals("DONE:OK_RESULT", lastValue.get());
    }

    @Test
    void submitFailedTaskWritesRunningThenFailed() {
        AtomicReference<String> lastValue = new AtomicReference<>();
        doAnswer(inv -> {
            lastValue.set(inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString());

        taskQueue.submit("tidErr", () -> {
            throw new RuntimeException("boom!");
        }, "jobs:");

        verify(valueOps, times(2)).set(anyString(), anyString());
        assertTrue(lastValue.get().startsWith("FAILED:"));
        assertTrue(lastValue.get().contains("boom!"));
    }
}
