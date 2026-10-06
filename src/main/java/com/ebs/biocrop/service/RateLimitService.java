package com.ebs.biocrop.service;

import com.ebs.biocrop.exception.AppException;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class RateLimitService {
    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    private static final int LOCAL_MAX_KEYS = 10_000;
    private static final Set<String> EMAIL_IDENTIFIER_POLICIES = Set.of(
            "email-otp-send", "email-otp-verify", "password-login-email", "password-reset-email");

    private static final DefaultRedisScript<List> INCREMENT_SCRIPT =
            new DefaultRedisScript<>("""
                    local count = redis.call('INCR', KEYS[1])
                    if count == 1 then
                        redis.call('PEXPIRE', KEYS[1], ARGV[1])
                    end
                    return {count, redis.call('PTTL', KEYS[1])}
                    """, List.class);

    private final StringRedisTemplate redisTemplate;
    private final Environment environment;
    private final SensitiveIdentifierHasher identifierHasher;
    private final Map<String, LocalWindow> localWindows = new HashMap<>();
    private final AtomicBoolean localFallbackWarningLogged = new AtomicBoolean();

    @Value("${app.rate-limit.dev-in-memory-fallback:false}")
    private boolean devInMemoryFallbackEnabled;

    @Value("${app.rate-limit.in-memory-fallback:false}")
    private boolean inMemoryFallbackEnabled;

    public RateLimitService(StringRedisTemplate redisTemplate, Environment environment,
                            SensitiveIdentifierHasher identifierHasher) {
        this.redisTemplate = redisTemplate;
        this.environment = environment;
        this.identifierHasher = identifierHasher;
    }

    /** Returns 0 when allowed; otherwise returns seconds until the counter expires. */
    public long check(String policy, String identifier, int maxRequests, Duration window) {
        String normalizedIdentifier = identifier.trim();
        if (EMAIL_IDENTIFIER_POLICIES.contains(policy)) {
            normalizedIdentifier = normalizedIdentifier.toLowerCase(java.util.Locale.ROOT);
        }
        String key = "rate-limit:" + policy + ":" + identifierHasher.hash(normalizedIdentifier);
        boolean fallbackAllowed = environment.acceptsProfiles(Profiles.of("dev"))
                ? devInMemoryFallbackEnabled
                : inMemoryFallbackEnabled;

        List<?> result;
        try {
            result = redisTemplate.execute(
                    INCREMENT_SCRIPT,
                    List.of(key),
                    Long.toString(window.toMillis())
            );
        } catch (Exception exception) {
            if (fallbackAllowed) {
                if (localFallbackWarningLogged.compareAndSet(false, true)) {
                    log.warn("Redis rate limiting is unavailable; using bounded process-local limits (exception type: {}).",
                            exception.getClass().getSimpleName());
                }
                return checkLocal(key, maxRequests, window);
            }
            throw new AppException("Rate limiting is temporarily unavailable. Please retry shortly.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }

        if (result == null || result.size() < 2) {
            if (fallbackAllowed) {
                if (localFallbackWarningLogged.compareAndSet(false, true)) {
                    log.warn("Redis rate limiting returned null/empty response; using bounded process-local limits.");
                }
                return checkLocal(key, maxRequests, window);
            }
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

    private synchronized long checkLocal(String key, int maxRequests, Duration window) {
        long now = System.nanoTime();
        long windowNanos = window.toNanos();
        LocalWindow current = localWindows.get(key);
        if (current == null || now - current.startedAtNanos >= windowNanos) {
            if (current == null && localWindows.size() >= LOCAL_MAX_KEYS) {
                removeExpiredLocalWindows(now, windowNanos);
                if (localWindows.size() >= LOCAL_MAX_KEYS) {
                    throw new AppException("Local development rate limiter is at capacity. Please retry shortly.",
                            HttpStatus.SERVICE_UNAVAILABLE);
                }
            }
            current = new LocalWindow(now);
            localWindows.put(key, current);
        }
        current.count++;
        if (current.count <= maxRequests) return 0;
        long remainingNanos = windowNanos - (now - current.startedAtNanos);
        return Math.max(1, (remainingNanos + 999_999_999L) / 1_000_000_000L);
    }

    private void removeExpiredLocalWindows(long now, long windowNanos) {
        Iterator<LocalWindow> iterator = localWindows.values().iterator();
        while (iterator.hasNext()) {
            LocalWindow entry = iterator.next();
            if (now - entry.startedAtNanos >= windowNanos) iterator.remove();
        }
    }

    private static final class LocalWindow {
        private final long startedAtNanos;
        private long count;

        private LocalWindow(long startedAtNanos) {
            this.startedAtNanos = startedAtNanos;
        }
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

}
