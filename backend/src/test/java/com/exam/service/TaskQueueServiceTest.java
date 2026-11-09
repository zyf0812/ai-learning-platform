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

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> ops;

    private TaskQueueService taskQueue;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(ops);
        taskQueue = new TaskQueueService(redis);
    }

    @Test
    void successfulTaskTransitionsFromRunningToDone() {
        taskQueue.submit("t1", () -> "payload", "task:");

        InOrder order = inOrder(ops);
        order.verify(ops).set("task:t1", "RUNNING");
        order.verify(ops).set("task:t1", "DONE:payload");
    }

    @Test
    void failingTaskIsMarkedFailedWithoutRethrowing() {
        assertDoesNotThrow(() -> taskQueue.submit("t2", () -> {
            throw new IllegalStateException("boom");
        }, "task:"));

        verify(ops).set("task:t2", "RUNNING");
        verify(ops).set("task:t2", "FAILED:boom");
    }

    @Test
    void statusLookupUsesPrefixAndTaskId() {
        when(ops.get("exam:task:t3")).thenReturn("DONE:exam9");

        assertEquals("DONE:exam9", taskQueue.getStatus("exam:task:", "t3"));
    }

    @Test
    void setAppliesTtlInSeconds() {
        taskQueue.set("auth:lock:alice", "locked", 900);

        verify(ops).set("auth:lock:alice", "locked", Duration.ofSeconds(900));
    }

    @Test
    void deleteRemovesKey() {
        taskQueue.del("auth:attempt:bob");

        verify(redis).delete("auth:attempt:bob");
    }

    @Test
    void generatedTaskIdIsEightCharacters() {
        String id = taskQueue.newTaskId();

        assertNotNull(id);
        assertEquals(8, id.length());
    }
}
