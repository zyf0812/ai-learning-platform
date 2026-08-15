package com.exam.service;

import com.exam.testutil.FakeTaskQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class TaskQueueServiceTest {

    private FakeTaskQueueService service;

    @BeforeEach
    void setUp() {
        service = new FakeTaskQueueService();
    }

    // ---------- newTaskId ----------

    @Test
    void newTaskIdReturns8Chars() {
        String id = service.newTaskId();
        assertEquals(8, id.length());
    }

    @Test
    void newTaskIdGeneratesUniqueValues() {
        assertNotEquals(service.newTaskId(), service.newTaskId());
    }

    // ---------- getStatus ----------

    @Test
    void getStatusConcatenatesPrefixAndTaskId() {
        service.store.put("pre:abc", "DONE:ok");

        String v = service.getStatus("pre:", "abc");
        assertEquals("DONE:ok", v);
    }

    @Test
    void getStatusReturnsNullWhenMissing() {
        assertNull(service.getStatus("pre:", "missing"));
    }

    // ---------- set ----------

    @Test
    void setStoresWithTtlSeconds() {
        service.set("k1", "v1", 300);

        assertEquals("v1", service.store.get("k1"));
        assertEquals(Long.valueOf(300), service.ttlStore.get("k1"));
    }

    @Test
    void setZeroTtl() {
        service.set("key", "val", 0);
        assertEquals("val", service.store.get("key"));
        assertEquals(Long.valueOf(0), service.ttlStore.get("key"));
    }

    // ---------- del ----------

    @Test
    void delRemovesFromStore() {
        service.store.put("del-me", "value");
        service.ttlStore.put("del-me", 10L);

        service.del("del-me");

        assertNull(service.store.get("del-me"));
        assertNull(service.ttlStore.get("del-me"));
    }

    // ---------- submit（同步执行 Supplier，因为单测无 Spring 上下文）----------

    @Test
    void submitSuccessWritesDonePrefix() {
        String taskId = "t1";
        Supplier<String> task = () -> "result-data";

        service.submit(taskId, task, "queue:");

        // 先 RUNNING 后 DONE:result-data（最终 store 中是后者，因为覆盖了）
        assertEquals("DONE:result-data", service.store.get("queue:t1"));
    }

    @Test
    void submitFailureWritesFailedPrefix() {
        Supplier<String> failing = () -> {
            throw new RuntimeException("boom!");
        };

        service.submit("t2", failing, "q:");

        String finalVal = service.store.get("q:t2");
        assertTrue(finalVal.startsWith("FAILED:"));
        assertTrue(finalVal.contains("boom!"));
    }

    @Test
    void submitTaskActuallyExecuted() {
        AtomicBoolean ran = new AtomicBoolean(false);
        Supplier<String> task = () -> {
            ran.set(true);
            return "ok";
        };

        service.submit("t3", task, "p:");
        assertTrue(ran.get());
        assertEquals("DONE:ok", service.store.get("p:t3"));
    }
}
