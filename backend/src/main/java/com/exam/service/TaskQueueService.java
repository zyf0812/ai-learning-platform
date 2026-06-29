package com.exam.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.function.Supplier;

@Service
public class TaskQueueService {

    private static final Logger log = LoggerFactory.getLogger(TaskQueueService.class);
    private final StringRedisTemplate redis;

    public TaskQueueService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Async
    public void submit(String taskId, Supplier<String> task, String prefix) {
        String key = prefix + taskId;
        log.info("[Redis SET] {} = RUNNING", key);
        redis.opsForValue().set(key, "RUNNING");
        try {
            String result = task.get();
            log.info("[Redis SET] {} = DONE", key);
            redis.opsForValue().set(key, "DONE:" + result);
        } catch (Exception e) {
            log.error("[Redis SET] {} = FAILED: {}", key, e.getMessage());
            redis.opsForValue().set(key, "FAILED:" + e.getMessage());
        }
    }

    public String getStatus(String prefix, String taskId) {
        String key = prefix + taskId;
        String val = redis.opsForValue().get(key);
        log.debug("[Redis GET] {} = {}", key, val);
        return val;
    }

    public void set(String key, String value, long ttlSeconds) {
        redis.opsForValue().set(key, value, java.time.Duration.ofSeconds(ttlSeconds));
    }

    public void del(String key) {
        redis.delete(key);
    }

    public String newTaskId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
