package com.ebs.biocrop.service;

import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.InvalidOtpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Stores only encoded OTPs and attempt counts in Redis, with an expiry on every entry. */
@Service
public class RedisOtpStore {
    private static final long VERIFICATION_LOCK_MILLIS = 15_000;

    private static final DefaultRedisScript<Long> SAVE_SCRIPT = new DefaultRedisScript<>("""
            redis.call('HSET', KEYS[1], 'entryId', ARGV[1], 'otpHash', ARGV[2], 'attempts', '0')
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<List> BEGIN_VERIFICATION_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then return {0} end
            local acquired = redis.call('SET', KEYS[2], ARGV[1], 'NX', 'PX', ARGV[2])
            if not acquired then return {-1} end
            local attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
            if attempts > tonumber(ARGV[3]) then
                redis.call('DEL', KEYS[1])
                redis.call('DEL', KEYS[2])
                return {-2}
            end
            return {1, attempts, redis.call('HGET', KEYS[1], 'otpHash'), redis.call('HGET', KEYS[1], 'entryId')}
            """, List.class);

    private static final DefaultRedisScript<Long> COMPLETE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[2]) ~= ARGV[1] then return 0 end
            if redis.call('HGET', KEYS[1], 'entryId') ~= ARGV[2] then
                redis.call('DEL', KEYS[2])
                return 0
            end
            redis.call('DEL', KEYS[1])
            redis.call('DEL', KEYS[2])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> REJECT_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[2]) ~= ARGV[1] then return -1 end
            if redis.call('HGET', KEYS[1], 'entryId') ~= ARGV[2] then
                redis.call('DEL', KEYS[2])
                return -1
            end
            local attempts = tonumber(redis.call('HGET', KEYS[1], 'attempts') or '0')
            if attempts >= tonumber(ARGV[3]) then redis.call('DEL', KEYS[1]) end
            redis.call('DEL', KEYS[2])
            return attempts
            """, Long.class);

    private static final DefaultRedisScript<Long> DELETE_IF_ENTRY_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'entryId') == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private static final Logger log = LoggerFactory.getLogger(RedisOtpStore.class);

    private final StringRedisTemplate redis;
    private final SensitiveIdentifierHasher identifierHasher;
    private final boolean inMemoryFallback;
    private final ConcurrentHashMap<String, LocalOtpEntry> localEntries = new ConcurrentHashMap<>();

    public RedisOtpStore(StringRedisTemplate redis, SensitiveIdentifierHasher identifierHasher) {
        this(redis, identifierHasher, false);
    }

    @Autowired
    public RedisOtpStore(
            StringRedisTemplate redis,
            SensitiveIdentifierHasher identifierHasher,
            @Value("${app.rate-limit.in-memory-fallback:false}") boolean inMemoryFallback) {
        this.redis = redis;
        this.identifierHasher = identifierHasher;
        this.inMemoryFallback = inMemoryFallback;
    }

    public String save(String channel, String identifier, String encodedOtp, Duration ttl) {
        String entryId = UUID.randomUUID().toString();
        String key = otpKey(channel, identifier);
        try {
            Long saved = redis.execute(SAVE_SCRIPT, List.of(key),
                    entryId, encodedOtp, Long.toString(ttl.toMillis()));
            if (saved == null || saved != 1L) throw unavailable();
            return entryId;
        } catch (AppException exception) {
            throw exception;
        } catch (Exception exception) {
            if (inMemoryFallback) {
                log.warn("Redis OTP store unavailable ({}); using process-local OTP store fallback.",
                        exception.getClass().getSimpleName());
                long now = System.currentTimeMillis();
                localEntries.put(key, new LocalOtpEntry(entryId, encodedOtp, 0, now + ttl.toMillis(), null, 0));
                if (localEntries.size() > 5000) {
                    localEntries.entrySet().removeIf(e -> e.getValue().expiresAtMillis <= now);
                }
                return entryId;
            }
            throw unavailable();
        }
    }

    public Verification beginVerification(String channel, String identifier, int maxAttempts) {
        String token = UUID.randomUUID().toString();
        String key = otpKey(channel, identifier);
        try {
            List<?> result = redis.execute(BEGIN_VERIFICATION_SCRIPT,
                    List.of(key, lockKey(channel, identifier)),
                    token, Long.toString(VERIFICATION_LOCK_MILLIS), Integer.toString(maxAttempts));
            if (result == null || result.isEmpty()) throw unavailable();
            long code = asLong(result.getFirst());
            if (code == 0) throw new InvalidOtpException("No active OTP request found. Please request a new code.");
            if (code == -1) throw new InvalidOtpException("OTP verification is already in progress. Please retry shortly.");
            if (code == -2) throw new InvalidOtpException("Maximum verification attempts exceeded. Please request a new OTP.");
            if (code != 1 || result.size() < 4) throw unavailable();
            return new Verification(token, String.valueOf(result.get(2)), String.valueOf(result.get(3)),
                    Math.toIntExact(asLong(result.get(1))));
        } catch (AppException exception) {
            throw exception;
        } catch (Exception exception) {
            if (inMemoryFallback) {
                return beginVerificationLocal(key, token, maxAttempts);
            }
            throw unavailable();
        }
    }

    public void complete(String channel, String identifier, Verification verification) {
        String key = otpKey(channel, identifier);
        try {
            Long completed = redis.execute(COMPLETE_SCRIPT,
                    List.of(key, lockKey(channel, identifier)),
                    verification.token(), verification.entryId());
            if (completed == null || completed != 1L) {
                throw new InvalidOtpException("The OTP request changed or expired. Please request a new code.");
            }
        } catch (InvalidOtpException exception) {
            throw exception;
        } catch (Exception exception) {
            if (inMemoryFallback) {
                completeLocal(key, verification);
                return;
            }
            throw unavailable();
        }
    }

    public int reject(String channel, String identifier, Verification verification, int maxAttempts) {
        String key = otpKey(channel, identifier);
        try {
            Long attempts = redis.execute(REJECT_SCRIPT,
                    List.of(key, lockKey(channel, identifier)),
                    verification.token(), verification.entryId(), Integer.toString(maxAttempts));
            if (attempts == null) throw unavailable();
            if (attempts < 0) throw new InvalidOtpException("The OTP request changed or expired. Please request a new code.");
            return Math.toIntExact(attempts);
        } catch (AppException exception) {
            throw exception;
        } catch (Exception exception) {
            if (inMemoryFallback) {
                return rejectLocal(key, verification, maxAttempts);
            }
            throw unavailable();
        }
    }

    public void deleteIfCurrent(String channel, String identifier, String entryId) {
        String key = otpKey(channel, identifier);
        localEntries.computeIfPresent(key, (k, e) -> e.entryId.equals(entryId) ? null : e);
        try {
            redis.execute(DELETE_IF_ENTRY_SCRIPT, List.of(key), entryId);
        } catch (Exception exception) {
            if (!inMemoryFallback) {
                throw unavailable();
            }
        }
    }

    private Verification beginVerificationLocal(String key, String token, int maxAttempts) {
        long now = System.currentTimeMillis();
        final Verification[] out = {null};
        final RuntimeException[] err = {null};
        localEntries.compute(key, (k, existing) -> {
            if (existing == null || existing.expiresAtMillis <= now) {
                err[0] = new InvalidOtpException("No active OTP request found. Please request a new code.");
                return null;
            }
            if (existing.lockToken != null && existing.lockExpiresAtMillis > now) {
                err[0] = new InvalidOtpException("OTP verification is already in progress. Please retry shortly.");
                return existing;
            }
            int nextAttempts = existing.attempts + 1;
            if (nextAttempts > maxAttempts) {
                err[0] = new InvalidOtpException("Maximum verification attempts exceeded. Please request a new OTP.");
                return null;
            }
            out[0] = new Verification(token, existing.encodedOtp, existing.entryId, nextAttempts);
            return new LocalOtpEntry(existing.entryId, existing.encodedOtp, nextAttempts,
                    existing.expiresAtMillis, token, now + VERIFICATION_LOCK_MILLIS);
        });
        if (err[0] != null) throw err[0];
        return out[0];
    }

    private void completeLocal(String key, Verification verification) {
        final boolean[] ok = {false};
        localEntries.computeIfPresent(key, (k, existing) -> {
            if (verification.token().equals(existing.lockToken) && verification.entryId().equals(existing.entryId)) {
                ok[0] = true;
                return null;
            }
            return existing;
        });
        if (!ok[0]) {
            throw new InvalidOtpException("The OTP request changed or expired. Please request a new code.");
        }
    }

    private int rejectLocal(String key, Verification verification, int maxAttempts) {
        final int[] attemptsOut = {-1};
        localEntries.computeIfPresent(key, (k, existing) -> {
            if (!verification.token().equals(existing.lockToken) || !verification.entryId().equals(existing.entryId)) {
                return existing;
            }
            attemptsOut[0] = existing.attempts;
            if (existing.attempts >= maxAttempts) {
                return null;
            }
            return new LocalOtpEntry(existing.entryId, existing.encodedOtp, existing.attempts,
                    existing.expiresAtMillis, null, 0);
        });
        if (attemptsOut[0] < 0) {
            throw new InvalidOtpException("The OTP request changed or expired. Please request a new code.");
        }
        return attemptsOut[0];
    }

    private static final class LocalOtpEntry {
        final String entryId;
        final String encodedOtp;
        final int attempts;
        final long expiresAtMillis;
        final String lockToken;
        final long lockExpiresAtMillis;

        LocalOtpEntry(String entryId, String encodedOtp, int attempts, long expiresAtMillis,
                      String lockToken, long lockExpiresAtMillis) {
            this.entryId = entryId;
            this.encodedOtp = encodedOtp;
            this.attempts = attempts;
            this.expiresAtMillis = expiresAtMillis;
            this.lockToken = lockToken;
            this.lockExpiresAtMillis = lockExpiresAtMillis;
        }
    }

    private String otpKey(String channel, String identifier) {
        return "otp:" + channel + ":" + identifierHasher.hash(identifier);
    }

    private String lockKey(String channel, String identifier) {
        return "otp-verify-lock:" + channel + ":" + identifierHasher.hash(identifier);
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
        return new AppException("OTP verification is temporarily unavailable. Please retry shortly.",
                HttpStatus.SERVICE_UNAVAILABLE);
    }

    public record Verification(String token, String encodedOtp, String entryId, int attempts) { }
}
