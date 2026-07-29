package com.exam.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

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
        // when redisTemplate.opsForValue() is called, return our mocked valueOps
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        taskQueue = new TaskQueueService(redisTemplate);
    }

    // ===== getStatus =====

    @Test
    void getStatusConcatenatesPrefixAndTaskId() {
        when(valueOps.get("exam:task:abc12345")).thenReturn("DONE:ok");
        String status = taskQueue.getStatus("exam:task:", "abc12345");
        assertEquals("DONE:ok", status);
        verify(valueOps).get("exam:task:abc12345");
    }

    @Test
    void getStatusEmptyPrefix() {
        // AuthService 使用空 prefix 的模式
        when(valueOps.get("auth:lock:user1")).thenReturn("locked");
        assertEquals("locked", taskQueue.getStatus("", "auth:lock:user1"));
        verify(valueOps).get("auth:lock:user1");
    }

    @Test
    void getStatusNotSetReturnsNull() {
        when(valueOps.get("any:key")).thenReturn(null);
        assertNull(taskQueue.getStatus("any:", "key"));
    }

    // ===== set =====

    @Test
    void setStoresWithTtl() {
        taskQueue.set("mykey", "myvalue", 600);
        verify(valueOps).set(eq("mykey"), eq("myvalue"), eq(Duration.ofSeconds(600)));
    }

    @Test
    void setWithZeroTtl() {
        taskQueue.set("k", "v", 0);
        verify(valueOps).set(eq("k"), eq("v"), eq(Duration.ofSeconds(0)));
    }

    // ===== del =====

    @Test
    void delCallsRedisDelete() {
        taskQueue.del("auth:attempt:someone");
        verify(redisTemplate).delete("auth:attempt:someone");
    }

    @Test
    void delCanBeCalledMultipleTimes() {
        taskQueue.del("a");
        taskQueue.del("b");
        verify(redisTemplate).delete("a");
        verify(redisTemplate).delete("b");
    }

    // ===== newTaskId =====

    @Test
    void newTaskIdReturns8CharString() {
        String id = taskQueue.newTaskId();
        assertNotNull(id);
        assertEquals(8, id.length());
    }

    @Test
    void newTaskIdGeneratesUniqueValues() {
        // 虽然是UUID substring前8位，仍有极低概率冲突；100次中重复概率可忽略
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 100; i++) {
            assertTrue(seen.add(taskQueue.newTaskId()),
                "newTaskId 不应重复生成相同ID(第"+i+"次冲突)");
        }
    }

    @Test
    void newTaskIdIsAlphanumericDash() {
        // UUID substring 前8位来自 UUID 标准: hex(8)-hex(4)-...
        String id = taskQueue.newTaskId();
        // 只包含 [0-9a-f]（UUID hex）
        assertTrue(id.matches("[0-9a-f-]+") || id.matches("[0-9a-f]+"),
            "newTaskId应只含十六进制字符(可能含'-'): " + id);
    }

    // ===== submit (异步) =====

    @Test
    void submitSuccessfulTaskSetsRunningThenDone() {
        // submit是@Async的，但在单元测试中Mockito不启用Spring异步，所以会同步执行
        taskQueue.submit("taskX", () -> "SUCCESS", "pre:");

        // 第一个set: RUNNING
        // 第二个set: DONE:SUCCESS
        var inorder = inOrder(valueOps);
        inorder.verify(valueOps).set("pre:taskX", "RUNNING");
        inorder.verify(valueOps).set("pre:taskX", "DONE:SUCCESS");
    }

    @Test
    void submitFailingTaskSetsRunningThenFailed() {
        RuntimeException ex = new RuntimeException("boom");

        taskQueue.submit("badTask", () -> { throw ex; }, "job:");

        var inorder = inOrder(valueOps);
        inorder.verify(valueOps).set("job:badTask", "RUNNING");
        inorder.verify(valueOps).set(eq("job:badTask"),
            argThat(v -> v != null && v.startsWith("FAILED:") && v.contains("boom")));
    }
}
