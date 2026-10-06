package com.ebs.biocrop.service;

import com.ebs.biocrop.exception.RateLimitExceededException;
import org.springframework.stereotype.Service;

import java.time.Duration;

/** Applies Redis-backed request limits to authenticated write operations. */
@Service
public class RateLimitGuard {
    private final RateLimitService rateLimits;

    public RateLimitGuard(RateLimitService rateLimits) {
        this.rateLimits = rateLimits;
    }

    public void enforce(String policy, String identifier, int maxRequests, Duration window) {
        long retryAfterSeconds = rateLimits.check(policy, identifier, maxRequests, window);
        if (retryAfterSeconds > 0) {
            throw new RateLimitExceededException(retryAfterSeconds);
        }
    }
}
