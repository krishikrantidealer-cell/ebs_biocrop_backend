package com.ebs.biocrop.service;

import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.InvalidOtpException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

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

    private final StringRedisTemplate redis;
    private final SensitiveIdentifierHasher identifierHasher;

    public RedisOtpStore(StringRedisTemplate redis, SensitiveIdentifierHasher identifierHasher) {
        this.redis = redis;
        this.identifierHasher = identifierHasher;
    }

    public String save(String channel, String identifier, String encodedOtp, Duration ttl) {
        String entryId = UUID.randomUUID().toString();
        try {
            Long saved = redis.execute(SAVE_SCRIPT, List.of(otpKey(channel, identifier)),
                    entryId, encodedOtp, Long.toString(ttl.toMillis()));
            if (saved == null || saved != 1L) throw unavailable();
            return entryId;
        } catch (AppException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unavailable();
        }
    }

    public Verification beginVerification(String channel, String identifier, int maxAttempts) {
        String token = UUID.randomUUID().toString();
        try {
            List<?> result = redis.execute(BEGIN_VERIFICATION_SCRIPT,
                    List.of(otpKey(channel, identifier), lockKey(channel, identifier)),
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
            throw unavailable();
        }
    }

    public void complete(String channel, String identifier, Verification verification) {
        try {
            Long completed = redis.execute(COMPLETE_SCRIPT,
                    List.of(otpKey(channel, identifier), lockKey(channel, identifier)),
                    verification.token(), verification.entryId());
            if (completed == null || completed != 1L) {
                throw new InvalidOtpException("The OTP request changed or expired. Please request a new code.");
            }
        } catch (InvalidOtpException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unavailable();
        }
    }

    public int reject(String channel, String identifier, Verification verification, int maxAttempts) {
        try {
            Long attempts = redis.execute(REJECT_SCRIPT,
                    List.of(otpKey(channel, identifier), lockKey(channel, identifier)),
                    verification.token(), verification.entryId(), Integer.toString(maxAttempts));
            if (attempts == null) throw unavailable();
            if (attempts < 0) throw new InvalidOtpException("The OTP request changed or expired. Please request a new code.");
            return Math.toIntExact(attempts);
        } catch (AppException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unavailable();
        }
    }

    public void deleteIfCurrent(String channel, String identifier, String entryId) {
        try {
            redis.execute(DELETE_IF_ENTRY_SCRIPT, List.of(otpKey(channel, identifier)), entryId);
        } catch (Exception exception) {
            throw unavailable();
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
