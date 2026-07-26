package com.exam.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskQueueServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Test
    void submitSuccessfulTaskStoresDoneStatus() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        TaskQueueService service = new TaskQueueService(redisTemplate);
        service.submit("task1", () -> "result", "prefix:");

        verify(valueOps).set("prefix:task1", "RUNNING");
        verify(valueOps).set("prefix:task1", "DONE:result");
    }

    @Test
    void submitFailedTaskStoresFailedStatus() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        TaskQueueService service = new TaskQueueService(redisTemplate);
        service.submit("task2", () -> { throw new RuntimeException("error"); }, "prefix:");

        verify(valueOps).set("prefix:task2", "RUNNING");
        verify(valueOps).set("prefix:task2", startsWith("FAILED:"));
    }

    @Test
    void getStatusReturnsStoredValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("prefix:task1")).thenReturn("DONE:result");

        TaskQueueService service = new TaskQueueService(redisTemplate);
        String status = service.getStatus("prefix:", "task1");

        assertEquals("DONE:result", status);
    }

    @Test
    void getStatusReturnsNullWhenNotExists() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("prefix:nonexistent")).thenReturn(null);

        TaskQueueService service = new TaskQueueService(redisTemplate);
        String status = service.getStatus("prefix:", "nonexistent");

        assertNull(status);
    }

    @Test
    void setWithTtlStoresValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        TaskQueueService service = new TaskQueueService(redisTemplate);
        service.set("key", "value", 3600);

        verify(valueOps).set(eq("key"), eq("value"), any(java.time.Duration.class));
    }

    @Test
    void delRemovesKey() {
        TaskQueueService service = new TaskQueueService(redisTemplate);
        service.del("key");

        verify(redisTemplate).delete("key");
    }

    @Test
    void newTaskIdGeneratesShortUuid() {
        TaskQueueService service = new TaskQueueService(redisTemplate);
        String taskId = service.newTaskId();

        assertEquals(8, taskId.length());
    }

    @Test
    void newTaskIdGeneratesDifferentIds() {
        TaskQueueService service = new TaskQueueService(redisTemplate);
        String id1 = service.newTaskId();
        String id2 = service.newTaskId();

        assertNotEquals(id1, id2);
    }

    private void assertEquals(String expected, String actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }

    private void assertNull(String actual) {
        org.junit.jupiter.api.Assertions.assertNull(actual);
    }

    private void assertNotEquals(String expected, String actual) {
        org.junit.jupiter.api.Assertions.assertNotEquals(expected, actual);
    }
}