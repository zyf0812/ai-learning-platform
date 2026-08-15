package com.exam.testutil;

import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 内存版 ValueOperations<String, String>。实现接口全部方法；
 * 未在本项目使用的方法直接抛出 UnsupportedOperationException。
 */
public class InMemoryValueOperations implements ValueOperations<String, String> {

    public final Map<String, String> store = new HashMap<>();
    public final Map<String, Duration> ttls = new HashMap<>();

    @Override public void set(String key, String value) { store.put(key, value); }
    @Override public void set(String key, String value, long timeout, TimeUnit unit) { store.put(key, value); }
    @Override public void set(String key, String value, Duration timeout) { store.put(key, value); if (timeout != null) ttls.put(key, timeout); }
    @Override public Boolean setIfAbsent(String key, String value) { return store.putIfAbsent(key, value) == null; }
    @Override public Boolean setIfAbsent(String key, String value, long timeout, TimeUnit unit) { return store.putIfAbsent(key, value) == null; }
    @Override public Boolean setIfAbsent(String key, String value, Duration timeout) { return store.putIfAbsent(key, value) == null; }
    @Override public Boolean setIfPresent(String key, String value) { throw new UnsupportedOperationException(); }
    @Override public Boolean setIfPresent(String key, String value, long timeout, TimeUnit unit) { throw new UnsupportedOperationException(); }
    @Override public Boolean setIfPresent(String key, String value, Duration timeout) { throw new UnsupportedOperationException(); }
    @Override public void multiSet(Map<? extends String, ? extends String> map) { throw new UnsupportedOperationException(); }
    @Override public Boolean multiSetIfAbsent(Map<? extends String, ? extends String> map) { throw new UnsupportedOperationException(); }
    @Override public String get(Object key) { return store.get(key); }
    @Override public String getAndDelete(String key) { return store.remove(key); }
    @Override public String getAndExpire(String key, long timeout, TimeUnit unit) { return store.get(key); }
    @Override public String getAndExpire(String key, Duration timeout) { return store.get(key); }
    @Override public String getAndPersist(String key) { return store.get(key); }
    @Override public String getAndSet(String key, String value) { return store.put(key, value); }
    @Override public List<String> multiGet(Collection<String> keys) { throw new UnsupportedOperationException(); }
    @Override public Long increment(String key) { return store.merge(key, "1", (a, b) -> String.valueOf(Long.parseLong(a) + 1)).equals("1") ? 1L : Long.parseLong(store.get(key)); }
    @Override public Long increment(String key, long delta) { throw new UnsupportedOperationException(); }
    @Override public Double increment(String key, double delta) { throw new UnsupportedOperationException(); }
    @Override public Long decrement(String key) { throw new UnsupportedOperationException(); }
    @Override public Long decrement(String key, long delta) { throw new UnsupportedOperationException(); }
    @Override public Integer append(String key, String value) { throw new UnsupportedOperationException(); }
    @Override public String get(String key, long start, long end) { throw new UnsupportedOperationException(); }
    @Override public void set(String key, String value, long offset) { throw new UnsupportedOperationException(); }
    @Override public Long size(String key) { String v = store.get(key); return v == null ? 0L : (long) v.length(); }
    @Override public Boolean setBit(String key, long offset, boolean value) { throw new UnsupportedOperationException(); }
    @Override public Boolean getBit(String key, long offset) { throw new UnsupportedOperationException(); }
    @Override public List<Long> bitField(String key, org.springframework.data.redis.connection.BitFieldSubCommands commands) { throw new UnsupportedOperationException(); }
    @Override public RedisOperations<String, String> getOperations() { throw new UnsupportedOperationException(); }
}
