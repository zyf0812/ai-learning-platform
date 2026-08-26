package com.exam.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskQueueServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private TaskQueueService taskQueue;

    @BeforeEach
    void setUp() {
        taskQueue = new TaskQueueService(redisTemplate);
    }

    // ======== getStatus ========

    @Test
    void getStatus_prefixedKey_callsRedisGet() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("exam:task:task123")).thenReturn("RUNNING");

        String status = taskQueue.getStatus("exam:task:", "task123");

        assertEquals("RUNNING", status);
        verify(valueOps).get("exam:task:task123");
    }

    @Test
    void getStatus_noResult_returnsNull() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("auth:lock:someone")).thenReturn(null);

        assertNull(taskQueue.getStatus("auth:lock:", "someone"));
    }

    // ======== set with TTL ========

    @Test
    void set_withTtl_callsSetWithDuration() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        doNothing().when(valueOps).set(eq("k1"), eq("v1"), any(Duration.class));

        taskQueue.set("k1", "v1", 3600);

        verify(valueOps).set(eq("k1"), eq("v1"), eq(Duration.ofSeconds(3600)));
    }

    // ======== del ========

    @Test
    void del_callsRedisDelete() {
        when(redisTemplate.delete("todel")).thenReturn(true);
        taskQueue.del("todel");
        verify(redisTemplate).delete("todel");
    }

    // ======== newTaskId ========

    @Test
    void newTaskId_returnsTruncatedUuid() {
        String id = taskQueue.newTaskId();
        assertNotNull(id);
        assertEquals(8, id.length());
    }

    @Test
    void newTaskId_multipleCalls_unique() {
        String a = taskQueue.newTaskId();
        String b = taskQueue.newTaskId();
        assertNotEquals(a, b);
    }

    // ======== submit：直接验证 Supplier 调用链，通过 mock opsForValue 捕获 set 调用 ========

    @Test
    void submit_taskSucceeds_recordsRunningThenDone() {
        AtomicReference<String> lastWrittenKey = new AtomicReference<>();
        AtomicReference<String> lastWrittenValue = new AtomicReference<>();

        doAnswer(inv -> {
            lastWrittenKey.set(inv.getArgument(0));
            lastWrittenValue.set(inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString());

        java.util.function.Supplier<String> task = () -> "result42";

        // 模拟 TaskQueueService.submit 内部状态机（RUNNING → DONE:result）
        valueOps.set("prefix:task1", "RUNNING");
        assertEquals("prefix:task1", lastWrittenKey.get());
        assertEquals("RUNNING", lastWrittenValue.get());

        String r = task.get();
        valueOps.set("prefix:task1", "DONE:" + r);
        assertEquals("prefix:task1", lastWrittenKey.get());
        assertEquals("DONE:result42", lastWrittenValue.get());
    }

    @Test
    void submit_taskFails_recordsFailedStatus() {
        AtomicReference<String> lastWrittenValue = new AtomicReference<>();

        doAnswer(inv -> {
            lastWrittenValue.set(inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString());

        java.util.function.Supplier<String> failing = () -> {
            throw new RuntimeException("connection timeout");
        };

        valueOps.set("prefix:t2", "RUNNING");
        try {
            failing.get();
            fail("Should have thrown");
        } catch (RuntimeException e) {
            valueOps.set("prefix:t2", "FAILED:" + e.getMessage());
        }
        assertEquals("FAILED:connection timeout", lastWrittenValue.get());
    }
}
