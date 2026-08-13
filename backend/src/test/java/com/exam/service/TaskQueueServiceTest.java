package com.exam.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    private TaskQueueService service;

    @BeforeEach
    void setUp() {
        // opsForValue() returns ValueOperations
        when(redis.opsForValue()).thenReturn(valueOps);
        service = new TaskQueueService(redis);
    }

    // ========== getStatus() ==========

    @Test
    void getStatusConcatenatesPrefixAndTaskId() {
        when(valueOps.get("pre:123")).thenReturn("DONE:result");

        String status = service.getStatus("pre:", "123");

        assertEquals("DONE:result", status);
    }

    @Test
    void getStatusEmptyPrefixUsesTaskIdAsKey() {
        when(valueOps.get("mytask")).thenReturn("RUNNING");

        assertEquals("RUNNING", service.getStatus("", "mytask"));
    }

    @Test
    void getStatusReturnsNullWhenNotFound() {
        when(valueOps.get("pre:absent")).thenReturn(null);

        assertNull(service.getStatus("pre:", "absent"));
    }

    // ========== set() ==========

    @Test
    void setStoresWithTtlDuration() {
        service.set("mykey", "val", 3600);

        verify(valueOps).set(eq("mykey"), eq("val"), eq(Duration.ofSeconds(3600)));
    }

    @Test
    void setZeroTtlStillWorks() {
        service.set("k", "v", 0);

        verify(valueOps).set(eq("k"), eq("v"), eq(Duration.ofSeconds(0)));
    }

    // ========== del() ==========

    @Test
    void delCallsRedisDelete() {
        service.del("oldkey");

        verify(redis).delete("oldkey");
    }

    // ========== newTaskId() ==========

    @Test
    void newTaskIdIs8Chars() {
        String id = service.newTaskId();

        assertNotNull(id);
        assertEquals(8, id.length());
    }

    @Test
    void newTaskIdMultipleCallsAreUnique() {
        String id1 = service.newTaskId();
        String id2 = service.newTaskId();
        String id3 = service.newTaskId();

        // 基于 UUID substring，几乎不可能重复
        assertNotEquals(id1, id2);
        assertNotEquals(id2, id3);
    }
}
