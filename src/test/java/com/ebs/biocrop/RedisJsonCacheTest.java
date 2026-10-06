package com.ebs.biocrop;

import com.ebs.biocrop.service.RedisJsonCache;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisJsonCacheTest {

    @Test
    void cacheMissLoadsMongoValueAndWritesWithTheRequestedTtl() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("cache-version:public-products")).thenReturn(null);
        String key = cacheKey("public-products", "0", "search=demo");
        when(values.get(key)).thenReturn(null);
        AtomicInteger loads = new AtomicInteger();

        String result = new RedisJsonCache(redis, new ObjectMapper()).getOrLoad(
                "public-products", "search=demo", new ObjectMapper().constructType(String.class),
                Duration.ofSeconds(30), () -> {
                    loads.incrementAndGet();
                    return "mongo-value";
                });

        assertEquals("mongo-value", result);
        assertEquals(1, loads.get());
        verify(values).set(key, "\"mongo-value\"", Duration.ofSeconds(30));
    }

    @Test
    void cacheHitReturnsCachedValueWithoutLoadingMongo() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("cache-version:public-blogs")).thenReturn("7");
        String key = cacheKey("public-blogs", "7", "slug=hello");
        when(values.get(key)).thenReturn("\"cached-value\"");

        String result = new RedisJsonCache(redis, new ObjectMapper()).getOrLoad(
                "public-blogs", "slug=hello", new ObjectMapper().constructType(String.class),
                Duration.ofMinutes(5), () -> {
                    throw new AssertionError("Mongo loader must not run on a cache hit");
                });

        assertEquals("cached-value", result);
        verify(values, never()).set(eq(key), anyString(), eq(Duration.ofMinutes(5)));
    }

    @Test
    void invalidationAdvancesOnlyTheRequestedRegionVersion() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);

        new RedisJsonCache(redis, new ObjectMapper()).invalidateRegion("public-categories");

        verify(values).increment("cache-version:public-categories");
        verify(values, never()).increment("cache-version:public-products");
    }

    private String cacheKey(String region, String version, String query) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(query.getBytes(StandardCharsets.UTF_8));
        return "cache:" + region + ":v" + version + ":" + HexFormat.of().formatHex(digest);
    }
}
