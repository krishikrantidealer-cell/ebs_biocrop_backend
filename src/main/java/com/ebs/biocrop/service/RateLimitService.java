package com.ebs.biocrop.service;

import com.ebs.biocrop.exception.AppException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

@Service
public class RateLimitService {

    private static final DefaultRedisScript<List> INCREMENT_SCRIPT =
            new DefaultRedisScript<>("""
                    local count = redis.call('INCR', KEYS[1])
                    if count == 1 then
                        redis.call('PEXPIRE', KEYS[1], ARGV[1])
                    end
                    return {count, redis.call('PTTL', KEYS[1])}
                    """, List.class);

    private final StringRedisTemplate redisTemplate;

    public RateLimitService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** Returns 0 when allowed; otherwise returns seconds until the counter expires. */
    public long check(String policy, String identifier, int maxRequests, Duration window) {
        String key = "rate-limit:" + policy + ":" + sha256(identifier);
        List<?> result;
        try {
            result = redisTemplate.execute(
                    INCREMENT_SCRIPT,
                    List.of(key),
                    Long.toString(window.toMillis())
            );
        } catch (DataAccessException exception) {
            throw new AppException("Rate limiting is temporarily unavailable. Please retry shortly.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }

        if (result == null || result.size() < 2) {
            throw new AppException("Rate limiting is temporarily unavailable. Please retry shortly.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }

        long count = asLong(result.get(0));
        long ttlMillis = asLong(result.get(1));
        if (count <= maxRequests) {
            return 0;
        }
        return Math.max(1, (ttlMillis + 999) / 1000);
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw new AppException("Rate limiting returned an invalid response.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash rate-limit key", exception);
        }
    }
}
