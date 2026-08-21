package com.exam.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskQueueServiceTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOps;

    private TaskQueueService taskQueue;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        taskQueue = new TaskQueueService(redis);
    }

    // ==================== getStatus ====================

    @Test
    void getStatus_prefixAndIdJoined() {
        when(redis.opsForValue().get("exam:task:abc123")).thenReturn("DONE:result");
        assertEquals("DONE:result", taskQueue.getStatus("exam:task:", "abc123"));
        verify(redis.opsForValue()).get("exam:task:abc123");
    }

    @Test
    void getStatus_emptyPrefix() {
        when(redis.opsForValue().get("auth:lock:u1")).thenReturn("locked");
        assertEquals("locked", taskQueue.getStatus("", "auth:lock:u1"));
    }

    @Test
    void getStatus_notPresentReturnsNull() {
        when(redis.opsForValue().get("x")).thenReturn(null);
        assertNull(taskQueue.getStatus("", "x"));
    }

    // ==================== set / del ====================

    @Test
    void set_withTtlSeconds() {
        taskQueue.set("mykey", "myval", 1800);
        verify(valueOps).set(eq("mykey"), eq("myval"), eq(Duration.ofSeconds(1800)));
    }

    @Test
    void del_deletesKey() {
        taskQueue.del("auth:attempt:u1");
        verify(redis).delete("auth:attempt:u1");
    }

    // ==================== newTaskId ====================

    @Test
    void newTaskId_8CharsNonEmptyUnique() {
        String id1 = taskQueue.newTaskId();
        String id2 = taskQueue.newTaskId();
        assertEquals(8, id1.length());
        assertEquals(8, id2.length());
        assertNotEquals(id1, id2);
    }

    // ==================== submit: 状态流转 ====================

    @Test
    void submit_successfulTaskMarksDone() throws Exception {
        // submit 是 @Async，我们直接同步调用 Supplier 以验证核心状态逻辑
        // 通过反射调用以便同步执行
        java.lang.reflect.Method submitMethod = TaskQueueService.class.getDeclaredMethod(
            "submit", String.class, Supplier.class, String.class
        );
        submitMethod.setAccessible(true);

        Supplier<String> task = () -> "OK:exam-001";
        // 执行（注意：@Async 在单元测试下不生效，会同步运行）
        submitMethod.invoke(taskQueue, "tid1", task, "prefix:");

        // RUNNING -> DONE:OK:exam-001
        InOrder order = inOrder(valueOps);
        order.verify(valueOps).set("prefix:tid1", "RUNNING");
        order.verify(valueOps).set("prefix:tid1", "DONE:OK:exam-001");
    }

    @Test
    void submit_failedTaskMarksFailed() throws Exception {
        java.lang.reflect.Method submitMethod = TaskQueueService.class.getDeclaredMethod(
            "submit", String.class, Supplier.class, String.class
        );
        submitMethod.setAccessible(true);

        Supplier<String> failing = () -> { throw new RuntimeException("boom"); };
        submitMethod.invoke(taskQueue, "tid2", failing, "p:");

        InOrder order = inOrder(valueOps);
        order.verify(valueOps).set("p:tid2", "RUNNING");
        order.verify(valueOps).set(eq("p:tid2"), startsWith("FAILED:"));
    }

    @Test
    void submit_taskActuallyExecutesOnce() throws Exception {
        java.lang.reflect.Method submitMethod = TaskQueueService.class.getDeclaredMethod(
            "submit", String.class, Supplier.class, String.class
        );
        submitMethod.setAccessible(true);

        AtomicBoolean ran = new AtomicBoolean(false);
        Supplier<String> task = () -> {
            ran.set(true);
            return "done";
        };
        submitMethod.invoke(taskQueue, "t3", task, "k:");
        assertTrue(ran.get());
    }
}
