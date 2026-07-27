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
import java.util.UUID;

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

    private TaskQueueService taskQueueService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        taskQueueService = new TaskQueueService(redisTemplate);
    }

    @Test
    void getStatusReturnsRedisValue() {
        when(valueOps.get("prefix:task123")).thenReturn("DONE:result");

        String status = taskQueueService.getStatus("prefix:", "task123");

        assertEquals("DONE:result", status);
        verify(valueOps).get("prefix:task123");
    }

    @Test
    void getStatusReturnsNullWhenNotExists() {
        when(valueOps.get("prefix:nonexistent")).thenReturn(null);

        String status = taskQueueService.getStatus("prefix:", "nonexistent");

        assertNull(status);
    }

    @Test
    void setStoresValueWithTtl() {
        ArgumentCaptor<Duration> durationCaptor = ArgumentCaptor.forClass(Duration.class);

        taskQueueService.set("mykey", "myvalue", 3600);

        verify(valueOps).set(eq("mykey"), eq("myvalue"), durationCaptor.capture());
        assertEquals(3600, durationCaptor.getValue().getSeconds());
    }

    @Test
    void delDeletesKey() {
        taskQueueService.del("mykey");

        verify(redisTemplate).delete("mykey");
    }

    @Test
    void newTaskIdReturnsValidId() {
        String taskId = taskQueueService.newTaskId();

        assertNotNull(taskId);
        assertFalse(taskId.isEmpty());
        assertTrue(taskId.length() <= 8);
    }

    @Test
    void newTaskIdGeneratesUniqueIds() {
        String id1 = taskQueueService.newTaskId();
        String id2 = taskQueueService.newTaskId();
        String id3 = taskQueueService.newTaskId();

        assertNotEquals(id1, id2);
        assertNotEquals(id2, id3);
        assertNotEquals(id1, id3);
    }

    @Test
    void submitTaskSetsRunningThenDoneOnSuccess() {
        when(valueOps.get("exam:task:task1")).thenReturn("RUNNING");

        taskQueueService.submit("task1", () -> "success-result", "exam:task:");

        verify(valueOps).set("exam:task:task1", "RUNNING");
        verify(valueOps).set("exam:task:task1", "DONE:success-result");
    }

    @Test
    void submitTaskSetsFailedOnException() {
        taskQueueService.submit("task1", () -> {
            throw new RuntimeException("something went wrong");
        }, "exam:task:");

        verify(valueOps).set("exam:task:task1", "RUNNING");
        verify(valueOps).set(eq("exam:task:task1"), contains("FAILED:"));
    }

    @Test
    void submitTaskWithDifferentPrefixes() {
        taskQueueService.submit("taskA", () -> "ok", "chat:task:");

        verify(valueOps).set("chat:task:taskA", "RUNNING");
        verify(valueOps).set("chat:task:taskA", "DONE:ok");
    }

    @Test
    void submitEmptyResult() {
        taskQueueService.submit("task1", () -> "", "prefix:");

        verify(valueOps).set("prefix:task1", "DONE:");
    }

    @Test
    void submitNullResultSupplierThrowsSetsFailed() {
        taskQueueService.submit("task1", () -> null, "prefix:");

        verify(valueOps).set("prefix:task1", "DONE:null");
    }
}
