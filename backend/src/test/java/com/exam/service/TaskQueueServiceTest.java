package com.exam.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskQueueServiceTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private TaskQueueService taskQueueService;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        taskQueueService = new TaskQueueService(redis);
    }

    @Test
    void submitMarksTaskRunningThenDone() {
        taskQueueService.submit("t1", () -> "42", "exam:task:");

        InOrder order = inOrder(valueOps);
        order.verify(valueOps).set("exam:task:t1", "RUNNING");
        order.verify(valueOps).set("exam:task:t1", "DONE:42");
    }

    @Test
    void submitMarksTaskFailedWhenSupplierThrows() {
        taskQueueService.submit("t2", () -> {
            throw new IllegalStateException("boom");
        }, "exam:task:");

        verify(valueOps).set("exam:task:t2", "RUNNING");
        verify(valueOps).set("exam:task:t2", "FAILED:boom");
    }

    @Test
    void getStatusReadsPrefixedKey() {
        when(valueOps.get("auth:lock:alice")).thenReturn("locked");

        assertEquals("locked", taskQueueService.getStatus("auth:lock:", "alice"));
    }

    @Test
    void setStoresValueWithTtlAndDelRemovesKey() {
        taskQueueService.set("auth:attempt:alice", "3", 1800);
        taskQueueService.del("auth:attempt:alice");

        verify(valueOps).set("auth:attempt:alice", "3", Duration.ofSeconds(1800));
        verify(redis).delete("auth:attempt:alice");
    }
}
