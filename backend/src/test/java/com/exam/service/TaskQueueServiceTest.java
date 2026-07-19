package com.exam.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class TaskQueueServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Test
    void getStatusReturnsValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("prefix:task123")).thenReturn("RUNNING");

        TaskQueueService service = new TaskQueueService(redisTemplate);
        String result = service.getStatus("prefix:", "task123");

        assertEquals("RUNNING", result);
        verify(valueOps).get("prefix:task123");
    }

    @Test
    void getStatusReturnsNullWhenKeyNotExists() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("prefix:nonexistent")).thenReturn(null);

        TaskQueueService service = new TaskQueueService(redisTemplate);
        String result = service.getStatus("prefix:", "nonexistent");

        assertNull(result);
    }

    @Test
    void setStoresValueWithTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        TaskQueueService service = new TaskQueueService(redisTemplate);
        service.set("mykey", "myvalue", 60);

        verify(valueOps).set(eq("mykey"), eq("myvalue"), any(Duration.class));
    }

    @Test
    void delRemovesKey() {
        when(redisTemplate.delete("mykey")).thenReturn(true);

        TaskQueueService service = new TaskQueueService(redisTemplate);
        service.del("mykey");

        verify(redisTemplate).delete("mykey");
    }

    @Test
    void newTaskIdGeneratesNonEmptyString() {
        TaskQueueService service = new TaskQueueService(redisTemplate);

        String id1 = service.newTaskId();
        String id2 = service.newTaskId();

        assertNotNull(id1);
        assertNotNull(id2);
        assertEquals(8, id1.length());
        assertNotEquals(id1, id2);
    }

    @Test
    void submitTaskSuccess() throws InterruptedException {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        TaskQueueService service = new TaskQueueService(redisTemplate);
        service.submit("task1", () -> "success_result", "prefix:");

        Thread.sleep(100);

        verify(valueOps).set(eq("prefix:task1"), eq("RUNNING"));
        verify(valueOps).set(eq("prefix:task1"), eq("DONE:success_result"));
    }

    @Test
    void submitTaskFailure() throws InterruptedException {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        TaskQueueService service = new TaskQueueService(redisTemplate);
        service.submit("task2", () -> { throw new RuntimeException("task failed"); }, "prefix:");

        Thread.sleep(100);

        verify(valueOps).set(eq("prefix:task2"), eq("RUNNING"));
        verify(valueOps).set(eq("prefix:task2"), eq("FAILED:task failed"));
    }
}