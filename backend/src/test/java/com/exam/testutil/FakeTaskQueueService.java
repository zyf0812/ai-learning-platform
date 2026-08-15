package com.exam.testutil;

import com.exam.service.TaskQueueService;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 内存版 TaskQueueService。不依赖 Redis，用 HashMap 存状态。
 * 用于 Java 25 下无法 mock 具体类时的替换。
 */
public class FakeTaskQueueService extends TaskQueueService {

    public final Map<String, String> store = new HashMap<>();
    public final Map<String, Long> ttlStore = new HashMap<>();

    public FakeTaskQueueService() {
        super(null); // parent 构造的 StringRedisTemplate 不会被用到
    }

    @Override
    public void submit(String taskId, Supplier<String> task, String prefix) {
        String key = prefix + taskId;
        store.put(key, "RUNNING");
        try {
            String result = task.get();
            store.put(key, "DONE:" + result);
        } catch (Exception e) {
            store.put(key, "FAILED:" + e.getMessage());
        }
    }

    @Override
    public String getStatus(String prefix, String taskId) {
        return store.get(prefix + taskId);
    }

    @Override
    public void set(String key, String value, long ttlSeconds) {
        store.put(key, value);
        ttlStore.put(key, ttlSeconds);
    }

    @Override
    public void del(String key) {
        store.remove(key);
        ttlStore.remove(key);
    }

    @Override
    public String newTaskId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
