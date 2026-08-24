package com.exam.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TaskQueueService 单元测试。
 *
 * 由于 StringRedisTemplate/ValueOperations 是复杂 Spring Data 接口，Mockito 在部分 JDK 下
 * 无法可靠 mock（final 类、模块系统限制等），这里使用 JDK 动态代理 + 内存 Map 构造轻量级
 * Redis 替身，依然可以覆盖任务状态流转、TTL、删除、ID 生成等关键业务逻辑。
 */
class TaskQueueServiceTest {

    /** 模拟 Redis 内存存储：key -> value */
    private final Map<String, String> store = new ConcurrentHashMap<>();
    /** 模拟 TTL 记录：key -> Duration（仅用于断言验证） */
    private final Map<String, Duration> ttlStore = new ConcurrentHashMap<>();

    private StringRedisTemplate redisProxy;
    private TaskQueueService taskQueue;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // 1) 构造 ValueOperations<String, String> 代理
        InvocationHandler valueOpsHandler = (_proxy, method, args) -> {
            String name = method.getName();
            return switch (name) {
                case "get" -> store.get(args[0]);
                case "set" -> {
                    String key = (String) args[0];
                    String value = (String) args[1];
                    store.put(key, value);
                    if (args.length == 3) {
                        ttlStore.put(key, (Duration) args[2]);
                    }
                    yield null;
                }
                case "getOperations" -> redisProxy;
                default -> throw new UnsupportedOperationException(
                        "ValueOperations." + name + " is not stubbed in unit tests");
            };
        };

        ValueOperations<String, String> valueOpsProxy = (ValueOperations<String, String>)
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[]{ValueOperations.class},
                        valueOpsHandler
                );

        // 2) 构造 StringRedisTemplate 代理（通过子类而非接口代理，避免 StringRedisTemplate
        //    中用到的非接口方法调用失败。此处使用普通子类并覆盖用到的方法）
        this.redisProxy = new InMemoryStringRedisTemplate(store, ttlStore, valueOpsProxy);
        this.taskQueue = new TaskQueueService(redisProxy);
    }

    @Test
    void getStatusReturnsRedisValue() {
        store.put("exam:task:abc123", "DONE:result");
        String status = taskQueue.getStatus("exam:task:", "abc123");
        assertEquals("DONE:result", status);
    }

    @Test
    void getStatusMissingKeyReturnsNull() {
        assertNull(taskQueue.getStatus("auth:lock:", "user1"));
    }

    @Test
    void setStoresValueWithTtl() {
        taskQueue.set("mykey", "myval", 300);
        assertEquals("myval", store.get("mykey"));
        assertEquals(Duration.ofSeconds(300), ttlStore.get("mykey"));
    }

    @Test
    void setZeroTtlWorks() {
        taskQueue.set("k", "v", 0);
        assertEquals("v", store.get("k"));
        assertEquals(Duration.ofSeconds(0), ttlStore.get("k"));
    }

    @Test
    void delCallsRedisDelete() {
        store.put("auth:attempt:user1", "3");
        taskQueue.del("auth:attempt:user1");
        assertNull(store.get("auth:attempt:user1"));
        assertFalse(store.containsKey("auth:attempt:user1"));
    }

    @Test
    void newTaskIdReturns8CharString() {
        String id1 = taskQueue.newTaskId();
        String id2 = taskQueue.newTaskId();

        assertNotNull(id1);
        assertEquals(8, id1.length());
        assertNotEquals(id1, id2);
    }

    @Test
    void submitSetsRunningThenDoneOnSuccess() {
        Supplier<String> task = () -> "OK: exam123";

        taskQueue.submit("task1", task, "prefix:");

        assertEquals("DONE:OK: exam123", store.get("prefix:task1"));
    }

    @Test
    void submitSetsFailedWhenTaskThrows() {
        Supplier<String> failingTask = () -> {
            throw new RuntimeException("boom");
        };

        taskQueue.submit("task2", failingTask, "q:");

        String stored = store.get("q:task2");
        assertNotNull(stored);
        assertTrue(stored.startsWith("FAILED:"), "expected FAILED: prefix, got: " + stored);
        assertTrue(stored.contains("boom"), "expected exception message in value, got: " + stored);
    }

    @Test
    void submitEmptyPrefixWorks() {
        Supplier<String> task = () -> "done";
        taskQueue.submit("t1", task, "");

        assertEquals("DONE:done", store.get("t1"));
    }

    // -------------------- 辅助类 --------------------

    /**
     * StringRedisTemplate 的测试专用子类：
     * 覆盖 TaskQueueService 实际会用到的 opsForValue() 和 delete()，
     * 避免真实 StringRedisTemplate 在初始化阶段尝试连接 Redis。
     */
    static class InMemoryStringRedisTemplate extends StringRedisTemplate {
        private final Map<String, String> store;
        private final Map<String, Duration> ttlStore;
        private final ValueOperations<String, String> valueOps;

        InMemoryStringRedisTemplate(Map<String, String> store,
                                     Map<String, Duration> ttlStore,
                                     ValueOperations<String, String> valueOps) {
            this.store = store;
            this.ttlStore = ttlStore;
            this.valueOps = valueOps;
        }

        @Override
        public ValueOperations<String, String> opsForValue() {
            return valueOps;
        }

        @Override
        public Boolean delete(String key) {
            store.remove(key);
            ttlStore.remove(key);
            return true;
        }
    }
}
