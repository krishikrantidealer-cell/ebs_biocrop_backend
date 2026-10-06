package com.ebs.biocrop.service;

import com.ebs.biocrop.exception.AppException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/** Distributed OTP resend cooldown reservations backed by Redis TTL keys. */
@Service
public class RedisOtpCooldown {
    private static final DefaultRedisScript<List> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
            local acquired = redis.call('SET', KEYS[1], ARGV[2], 'NX', 'PX', ARGV[1])
            if acquired then return {1, 0} end
            return {0, redis.call('PTTL', KEYS[1])}
            """, List.class);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final SensitiveIdentifierHasher identifierHasher;

    public RedisOtpCooldown(StringRedisTemplate redis, SensitiveIdentifierHasher identifierHasher) {
        this.redis = redis;
        this.identifierHasher = identifierHasher;
    }

    /** Returns zero when acquired, otherwise the retry delay in seconds. */
    public long acquire(String channel, String identifier, Duration cooldown, String reservationToken) {
        String key = key(channel, identifier);
        try {
            List<?> result = redis.execute(ACQUIRE_SCRIPT, List.of(key),
                    Long.toString(cooldown.toMillis()), reservationToken);
            if (result == null || result.size() < 2) throw unavailable();
            long acquired = asLong(result.get(0));
            if (acquired == 1) return 0;
            long ttlMillis = asLong(result.get(1));
            return Math.max(1, (ttlMillis + 999) / 1000);
        } catch (AppException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unavailable();
        }
    }

    /** Releases only this request's reservation, and only after a delivery failure. */
    public void release(String channel, String identifier, String reservationToken) {
        try {
            redis.execute(RELEASE_SCRIPT, List.of(key(channel, identifier)), reservationToken);
        } catch (Exception ignored) {
            // The short-lived key expires automatically if Redis cannot release it.
        }
    }

    private String key(String channel, String identifier) {
        return "otp-cooldown:" + channel + ":" + identifierHasher.hash(identifier);
    }

    private long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw unavailable();
        }
    }

    private AppException unavailable() {
        return new AppException("OTP delivery protection is temporarily unavailable. Please retry shortly.",
                HttpStatus.SERVICE_UNAVAILABLE);
    }
}
