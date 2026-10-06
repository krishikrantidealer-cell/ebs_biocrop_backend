package com.ebs.biocrop.service;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Small best-effort JSON cache for public read responses. MongoDB remains authoritative. */
@Service
public class RedisJsonCache {
    private static final Logger log = LoggerFactory.getLogger(RedisJsonCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean cacheWarningLogged = new AtomicBoolean();

    public RedisJsonCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public <T> T getOrLoad(String region, String queryKey, JavaType valueType,
                           Duration ttl, Supplier<T> loader) {
        String versionKey = versionKey(region);
        String version;
        try {
            version = redis.opsForValue().get(versionKey);
            if (version == null) version = "0";
        } catch (RuntimeException exception) {
            warnOnce("Redis cache read unavailable; loading public data from MongoDB.", exception);
            return loader.get();
        }

        String key = "cache:" + region + ":v" + version + ":" + sha256(queryKey);
        try {
            String json = redis.opsForValue().get(key);
            if (json != null) {
                try {
                    return objectMapper.readValue(json, valueType);
                } catch (Exception serializationFailure) {
                    redis.delete(key);
                    warnOnce("A Redis cache entry could not be decoded; rebuilding it from MongoDB.", serializationFailure);
                }
            }
        } catch (RuntimeException exception) {
            warnOnce("Redis cache read unavailable; loading public data from MongoDB.", exception);
            return loader.get();
        }

        T value = loader.get();
        try {
            String json = objectMapper.writeValueAsString(value);
            redis.opsForValue().set(key, json, ttl);
        } catch (Exception exception) {
            warnOnce("Redis cache write unavailable; returning data loaded from MongoDB.", exception);
        }
        return value;
    }

    /** Invalidates every cached query in a region without scanning Redis keys. */
    public void invalidateRegion(String region) {
        try {
            redis.opsForValue().increment(versionKey(region));
        } catch (RuntimeException exception) {
            warnOnce("Redis cache invalidation failed; cached entries will expire by TTL.", exception);
        }
    }

    /** Defers invalidation until a surrounding MongoDB transaction commits. */
    public void invalidateRegionAfterCommit(String region) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invalidateRegion(region);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidateRegion(region);
            }
        });
    }

    private String versionKey(String region) {
        return "cache-version:" + region;
    }

    private void warnOnce(String message, Exception exception) {
        if (cacheWarningLogged.compareAndSet(false, true)) {
            log.warn("{} (exception type: {}).", message, exception.getClass().getSimpleName());
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash Redis cache key", exception);
        }
    }
}
