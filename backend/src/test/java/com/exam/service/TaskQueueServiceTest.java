package com.exam.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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
        // 由于 redisTemplate.opsForValue() 返回的是 mock，我们需要 stub
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        taskQueue = new TaskQueueService(redisTemplate);
    }

    // ===== getStatus 测试 =====

    @Test
    void getStatusReturnsRedisValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("prefix:task123")).thenReturn("DONE:result");

        String status = taskQueue.getStatus("prefix:", "task123");

        assertEquals("DONE:result", status);
        verify(valueOps).get("prefix:task123");
    }

    @Test
    void getStatusWithEmptyPrefix() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("auth:lock:userA")).thenReturn("locked");

        assertEquals("locked", taskQueue.getStatus("", "auth:lock:userA"));
    }

    @Test
    void getStatusReturnsNullWhenMissing() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("p:nonexistent")).thenReturn(null);

        assertNull(taskQueue.getStatus("p:", "nonexistent"));
    }

    // ===== set 测试 =====

    @Test
    void setStoresWithTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        taskQueue.set("mykey", "myval", 1800);

        ArgumentCaptor<Duration> durationCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(eq("mykey"), eq("myval"), durationCaptor.capture());
        assertEquals(1800, durationCaptor.getValue().getSeconds());
    }

    // ===== del 测试 =====

    @Test
    void delCallsRedisDelete() {
        taskQueue.del("auth:attempt:bob");
        verify(redisTemplate).delete("auth:attempt:bob");
    }

    // ===== newTaskId 测试 =====

    @Test
    void newTaskIdIs8Chars() {
        String id = taskQueue.newTaskId();
        assertNotNull(id);
        assertEquals(8, id.length());
        // UUID 字符集：只包含 hex
        assertTrue(id.matches("[0-9a-f]{8}"));
    }

    @Test
    void newTaskIdUniqueAcrossCalls() {
        String id1 = taskQueue.newTaskId();
        String id2 = taskQueue.newTaskId();
        assertNotEquals(id1, id2);
    }

    // ===== submit 测试：需要跳过 @Async，直接调用同步逻辑验证状态流转 =====

    @Test
    void submitSuccessFlow() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        // 同步调用 submit 方法体（忽略 @Async 注解，单元测试层面）
        taskQueue.submit("abc", () -> "ok-result", "jobs:");

        // 先 RUNNING，后 DONE:result
        var order = inOrder(valueOps);
        order.verify(valueOps).set("jobs:abc", "RUNNING");
        order.verify(valueOps).set("jobs:abc", "DONE:ok-result");
    }

    @Test
    void submitExceptionMarksFailed() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        taskQueue.submit("t1", () -> {
            throw new RuntimeException("boom");
        }, "q:");

        var order = inOrder(valueOps);
        order.verify(valueOps).set("q:t1", "RUNNING");
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        order.verify(valueOps).set(eq("q:t1"), captor.capture());
        assertTrue(captor.getValue().startsWith("FAILED:"));
        assertTrue(captor.getValue().contains("boom"));
    }

    @Test
    void submitUsesFullKeyPrefix() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        taskQueue.submit("id42", () -> "x", "exam:task:");

        verify(valueOps, atLeastOnce()).set(startsWith("exam:task:id42"), anyString());
    }
}
