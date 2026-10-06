package com.ebs.biocrop;

import com.ebs.biocrop.exception.GlobalExceptionHandler;
import com.ebs.biocrop.exception.OtpCooldownException;
import com.ebs.biocrop.service.RedisOtpCooldown;
import com.ebs.biocrop.service.SensitiveIdentifierHasher;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisOtpCooldownTest {
    private RedisOtpCooldown cooldown(StringRedisTemplate redis) {
        return new RedisOtpCooldown(redis, new SensitiveIdentifierHasher(null,
                "unit-test-hmac-secret-minimum-32-bytes"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void acquireReturnsZeroWhenRedisAtomicallyReservesCooldown() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(1L, 0L));

        long retryAfter = cooldown(redis).acquire(
                "email-send", "TEST@example.com", java.time.Duration.ofSeconds(60), "reservation");

        assertEquals(0, retryAfter);
    }

    @SuppressWarnings("unchecked")
    @Test
    void acquireRoundsRemainingTtlUpToRetryAfterSeconds() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(0L, 59_001L));

        long retryAfter = cooldown(redis).acquire(
                "phone-send", "9876543210", java.time.Duration.ofSeconds(60), "reservation");

        assertEquals(60, retryAfter);
    }

    @Test
    void cooldownRejectionIncludesRetryAfterHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/auth/email/otp/send");

        var response = new GlobalExceptionHandler().handleOtpCooldown(
                new OtpCooldownException("Please wait before requesting another OTP", 37), request);

        assertEquals(429, response.getStatusCode().value());
        assertEquals("37", response.getHeaders().getFirst("Retry-After"));
    }
}
