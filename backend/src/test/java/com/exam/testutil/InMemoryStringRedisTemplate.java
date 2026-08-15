package com.exam.testutil;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 内存实现的 StringRedisTemplate，只覆盖本项目测试实际使用的方法。
 * 避免 Mockito 在 Java 25 下无法 mock StringRedisTemplate。
 */
public class InMemoryStringRedisTemplate extends StringRedisTemplate {

    public final InMemoryValueOperations valueOps = new InMemoryValueOperations();

    @Override
    public ValueOperations<String, String> opsForValue() {
        return valueOps;
    }

    @Override
    public Boolean delete(String key) {
        return valueOps.store.remove(key) != null;
    }

    @Override
    public Long delete(Collection<String> keys) {
        long count = 0;
        for (String k : keys) if (valueOps.store.remove(k) != null) count++;
        return count;
    }

    @Override
    public Boolean expire(String key, long timeout, TimeUnit unit) {
        return false;
    }
}
