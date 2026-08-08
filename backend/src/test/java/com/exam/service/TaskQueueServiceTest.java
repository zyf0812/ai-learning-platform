package com.exam.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

    private TaskQueueService taskQueue;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        taskQueue = new TaskQueueService(redis);
    }

    @Nested
    @DisplayName("submit() tests")
    class SubmitTests {

        @Test
        @DisplayName("sets RUNNING status, executes task, sets DONE")
        void setsRunningThenDone() {
            String result = taskQueue.submit("task-1", () -> "success", "prefix:");

            verify(valueOps).set("prefix:task-1", "RUNNING");
            verify(valueOps).set("prefix:task-1", "DONE:success");
        }

        @Test
        @DisplayName("sets FAILED status when task throws exception")
        void setsFailedOnException() {
            taskQueue.submit("task-1", () -> {
                throw new RuntimeException("Task failed");
            }, "prefix:");

            verify(valueOps).set("prefix:task-1", "RUNNING");
            verify(valueOps).set(
                eq("prefix:task-1"),
                argThat(v -> v.startsWith("FAILED:Task failed"))
            );
        }
    }

    @Nested
    @DisplayName("getStatus() tests")
    class GetStatusTests {

        @Test
        @DisplayName("returns status from redis")
        void returnsStatus() {
            when(valueOps.get("prefix:task-1")).thenReturn("DONE:result");

            String status = taskQueue.getStatus("prefix:", "task-1");

            assertEquals("DONE:result", status);
        }

        @Test
        @DisplayName("returns null when key does not exist")
        void returnsNullWhenNotFound() {
            when(valueOps.get("prefix:unknown")).thenReturn(null);

            String status = taskQueue.getStatus("prefix:", "unknown");

            assertNull(status);
        }
    }

    @Nested
    @DisplayName("set() tests")
    class SetTests {

        @Test
        @DisplayName("sets key with TTL")
        void setsWithTtl() {
            taskQueue.set("myKey", "myValue", 300);

            verify(valueOps).set("myKey", "myValue", Duration.ofSeconds(300));
        }
    }

    @Nested
    @DisplayName("del() tests")
    class DelTests {

        @Test
        @DisplayName("deletes key")
        void deletesKey() {
            taskQueue.del("myKey");

            verify(redis).delete("myKey");
        }
    }

    @Nested
    @DisplayName("newTaskId() tests")
    class NewTaskIdTests {

        @Test
        @DisplayName("generates 8-character unique IDs")
        void generatesUniqueIds() {
            String id1 = taskQueue.newTaskId();
            String id2 = taskQueue.newTaskId();

            assertNotNull(id1);
            assertEquals(8, id1.length());
            assertNotEquals(id1, id2);
        }
    }
}