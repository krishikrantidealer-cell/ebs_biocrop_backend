package com.ebs.biocrop;

import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.service.RateLimitService;
import com.ebs.biocrop.service.SensitiveIdentifierHasher;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitServiceTest {
    private static final String TEST_HMAC_SECRET = "unit-test-hmac-secret-minimum-32-bytes";

    private RateLimitService service(StringRedisTemplate redis, Environment environment) {
        return new RateLimitService(redis, environment,
                new SensitiveIdentifierHasher(environment, TEST_HMAC_SECRET));
    }

    @SuppressWarnings("unchecked")
    @Test
    void allowsRequestsWithinLimit() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(org.springframework.core.env.Profiles.class))).thenReturn(false);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(3L, 60_000L));

        long retryAfter = service(redis, environment)
                .check("test-policy", "test-user", 3, Duration.ofMinutes(1));

        assertEquals(0, retryAfter);
    }

    @SuppressWarnings("unchecked")
    @Test
    void returnsRemainingWindowWhenLimitIsExceeded() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(org.springframework.core.env.Profiles.class))).thenReturn(false);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(4L, 59_001L));

        long retryAfter = service(redis, environment)
                .check("test-policy", "test-user", 3, Duration.ofMinutes(1));

        assertEquals(60, retryAfter);
    }

    @SuppressWarnings("unchecked")
    @Test
    void failsClosedWhenRedisIsUnavailableByDefault() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(org.springframework.core.env.Profiles.class))).thenReturn(false);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("Redis unavailable"));

        AppException exception = assertThrows(AppException.class, () -> service(redis, environment)
                .check("test-policy", "test-user", 3, Duration.ofMinutes(1)));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
    }

    @SuppressWarnings("unchecked")
    @Test
    void normalizesEmailIdentityBeforeHmacHashingRateLimitKey() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(org.springframework.core.env.Profiles.class))).thenReturn(false);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(List.of(1L, 900_000L));

        service(redis, environment)
                .check("email-otp-send", "  Test.User@Example.com ", 3, Duration.ofMinutes(15));

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TEST_HMAC_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String normalizedHash = "h1:" + HexFormat.of().formatHex(mac.doFinal(
                "test.user@example.com".getBytes(StandardCharsets.UTF_8)));
        verify(redis).execute(any(RedisScript.class),
                org.mockito.ArgumentMatchers.eq(List.of("rate-limit:email-otp-send:" + normalizedHash)),
                any(Object[].class));
    }
}
