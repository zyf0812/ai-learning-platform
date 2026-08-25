package com.exam.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskQueueServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private TaskQueueService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new TaskQueueService(redisTemplate);
    }

    // ============ getStatus 测试 ============

    @Test
    void getStatusReturnsRedisValue() {
        when(valueOps.get("prefixtask-1")).thenReturn("DONE:result");

        String status = service.getStatus("prefix", "task-1");

        assertEquals("DONE:result", status);
        verify(valueOps).get("prefixtask-1");
    }

    @Test
    void getStatusWithEmptyPrefix() {
        when(valueOps.get("mykey")).thenReturn("RUNNING");

        assertEquals("RUNNING", service.getStatus("", "mykey"));
    }

    @Test
    void getStatusReturnsNullWhenMissing() {
        when(valueOps.get("pre:taskX")).thenReturn(null);

        assertNull(service.getStatus("pre:", "taskX"));
    }

    // ============ set 测试 ============

    @Test
    void setStoresValueWithTtl() {
        service.set("lock:u1", "locked", 900);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(eq("lock:u1"), eq("locked"), ttlCaptor.capture());
        assertEquals(900, ttlCaptor.getValue().getSeconds());
    }

    @Test
    void setWithZeroTtl() {
        service.set("k", "v", 0);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(eq("k"), eq("v"), ttlCaptor.capture());
        assertEquals(0, ttlCaptor.getValue().getSeconds());
    }

    // ============ del 测试 ============

    @Test
    void delCallsRedisDelete() {
        service.del("attempt:u1");

        verify(redisTemplate).delete("attempt:u1");
    }

    @Test
    void delCanBeCalledMultipleTimes() {
        service.del("k1");
        service.del("k2");

        verify(redisTemplate).delete("k1");
        verify(redisTemplate).delete("k2");
    }

    // ============ newTaskId 测试 ============

    @Test
    void newTaskIdReturns8CharString() {
        String id = service.newTaskId();

        assertNotNull(id);
        assertEquals(8, id.length());
    }

    @Test
    void newTaskIdGeneratesUniqueValues() {
        String id1 = service.newTaskId();
        String id2 = service.newTaskId();

        assertNotEquals(id1, id2);
    }

    @Test
    void newTaskIdCharsAreValidHexOrUuidPart() {
        String id = service.newTaskId();
        // UUID.substring(0,8) 只包含 0-9a-f
        assertTrue(id.matches("[0-9a-f]{8}"));
    }

    // ============ submit 异步任务状态追踪 ============

    @Test
    void submitWithSuccessfulTaskMarksDone() {
        // submit 是 @Async，我们直接同步调用（mock 环境）
        service.submit("task1", () -> "ok-result", "jobs:");

        InOrder inOrder = inOrder(valueOps);
        inOrder.verify(valueOps).set("jobs:task1", "RUNNING");
        inOrder.verify(valueOps).set("jobs:task1", "DONE:ok-result");
    }

    @Test
    void submitWithFailingTaskMarksFailed() {
        service.submit("task2", () -> {
            throw new RuntimeException("boom");
        }, "jobs:");

        InOrder inOrder = inOrder(valueOps);
        inOrder.verify(valueOps).set("jobs:task2", "RUNNING");
        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        inOrder.verify(valueOps).set(eq("jobs:task2"), statusCaptor.capture());
        assertTrue(statusCaptor.getValue().startsWith("FAILED:"));
        assertTrue(statusCaptor.getValue().contains("boom"));
    }

    @Test
    void submitSuccessOverwritesRunningStatus() {
        service.submit("t", () -> "done", "");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps, times(2)).set(keyCaptor.capture(), valCaptor.capture());

        assertEquals("RUNNING", valCaptor.getAllValues().get(0));
        assertEquals("DONE:done", valCaptor.getAllValues().get(1));
    }
}
