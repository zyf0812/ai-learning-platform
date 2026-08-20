package com.exam.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskQueueServiceTest {

    // In-memory fake for StringRedisTemplate: overrides just the methods TaskQueueService uses.
    // Avoids Mockito inline mock maker failure on JDK 25 when mocking concrete Spring classes.
    static class FakeStringRedisTemplate extends StringRedisTemplate {
        final Map<String, String> valueStore = new ConcurrentHashMap<>();
        final Map<String, Long> ttlAtMs = new ConcurrentHashMap<>();
        final long startMs = System.currentTimeMillis();

        @Override
        public ValueOperations<String, String> opsForValue() {
            ValueOperations<String, String> v = mock(ValueOperations.class);
            // set(k, v, Duration) — used in set()
            doAnswer(inv -> {
                String k = inv.getArgument(0);
                String val = inv.getArgument(1);
                Object dur = inv.getArgument(2);
                valueStore.put(k, val);
                if (dur instanceof Duration d && !d.isZero() && !d.isNegative()) {
                    ttlAtMs.put(k, startMs + d.toMillis());
                }
                return null;
            }).when(v).set(anyString(), anyString(), any(Duration.class));
            // set(k, v) — used in submit() where no ttl given
            doAnswer(inv -> {
                valueStore.put(inv.getArgument(0), inv.getArgument(1));
                return null;
            }).when(v).set(anyString(), anyString());
            // get(k) — used in getStatus
            doAnswer(inv -> valueStore.get(inv.getArgument(0))).when(v).get(anyString());
            when(v.getOperations()).thenReturn(this);
            return v;
        }

        @Override
        public Boolean delete(String key) {
            valueStore.remove(key);
            ttlAtMs.remove(key);
            return true;
        }
    }

    private TaskQueueService service;
    private FakeStringRedisTemplate fakeRedis;

    @BeforeEach
    void setUp() {
        fakeRedis = new FakeStringRedisTemplate();
        service = new TaskQueueService(fakeRedis);
    }

    // ========= set / getStatus (round-trip) =========

    @Test
    void setStoresValueAndTtl() {
        service.set("k1", "v1", 120);
        assertEquals("v1", fakeRedis.valueStore.get("k1"));
        assertTrue(fakeRedis.ttlAtMs.containsKey("k1"));
    }

    @Test
    void setZeroTtlHasNoExpiry() {
        service.set("k2", "v2", 0);
        assertEquals("v2", fakeRedis.valueStore.get("k2"));
        assertFalse(fakeRedis.ttlAtMs.containsKey("k2"));
    }

    @Test
    void getStatusReturnsStoredValue() {
        service.set("p:t1", "DONE:ok", 0);
        assertEquals("DONE:ok", service.getStatus("p:", "t1"));
    }

    @Test
    void getStatusMissingReturnsNull() {
        assertNull(service.getStatus("p:", "no-such-id"));
    }

    // ========= del =========

    @Test
    void delRemovesValue() {
        service.set("k3", "v3", 0);
        assertNotNull(fakeRedis.valueStore.get("k3"));
        service.del("k3");
        assertNull(fakeRedis.valueStore.get("k3"));
        assertNull(service.getStatus("", "k3"));
    }

    // ========= newTaskId =========

    @Test
    void newTaskIdReturns8CharPrefixes() {
        String id = service.newTaskId();
        assertNotNull(id);
        assertEquals(8, id.length());
    }

    @Test
    void newTaskIdReturnsUniqueValues() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(service.newTaskId());
        }
        assertEquals(200, seen.size(), "UUID prefixes should not collide over 200 calls");
    }

    // ========= submit (synchronous in tests; no real @Async without Spring context) =========

    @Test
    void submitSuccessWritesDoneWithResult() {
        String id = service.newTaskId();
        service.submit(id, () -> "hello", "tasks:");
        assertEquals("DONE:hello", service.getStatus("tasks:", id));
    }

    @Test
    void submitFailureWritesFailedWithMessage() {
        String id = service.newTaskId();
        service.submit(id, () -> {
            throw new RuntimeException("boom");
        }, "tasks:");
        String status = service.getStatus("tasks:", id);
        assertNotNull(status);
        assertTrue(status.startsWith("FAILED:"), "Expected FAILED: prefix, got: " + status);
        assertTrue(status.contains("boom"));
    }
}
