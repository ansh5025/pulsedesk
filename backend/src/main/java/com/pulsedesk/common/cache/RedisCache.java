package com.pulsedesk.common.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** JSON read-through cache on Redis. Redis outages never break a request: the loader is used instead. */
@Component
public class RedisCache {

    private static final Logger log = LoggerFactory.getLogger(RedisCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisCache(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public <T> T getOrCompute(String key, Class<T> type, Duration ttl, Supplier<T> loader) {
        if (ttl.isZero() || ttl.isNegative()) {
            return loader.get();
        }
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return mapper.readValue(cached, type);
            }
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("Cache read failed for {}: {}", key, e.getMessage());
        }
        T value = loader.get();
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(value), ttl);
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("Cache write failed for {}: {}", key, e.getMessage());
        }
        return value;
    }
}
