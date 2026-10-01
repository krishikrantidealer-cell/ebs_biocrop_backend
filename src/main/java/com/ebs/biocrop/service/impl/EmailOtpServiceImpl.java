package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.exception.InvalidOtpException;
import com.ebs.biocrop.exception.OtpCooldownException;
import com.ebs.biocrop.service.EmailDeliveryService;
import com.ebs.biocrop.service.EmailOtpService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class EmailOtpServiceImpl implements EmailOtpService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();
    private final PasswordEncoder encoder;
    private final EmailDeliveryService delivery;
    @Value("${app.otp.expiration-minutes:5}") private int expiryMinutes;
    @Value("${app.otp.cooldown-seconds:60}") private int cooldownSeconds;
    @Value("${app.otp.max-attempts:3}") private int maxAttempts;
    @Value("${app.otp.cooldown-seconds:60}") private int configuredCooldownSeconds;

    public EmailOtpServiceImpl(PasswordEncoder encoder, EmailDeliveryService delivery) {
        this.encoder = encoder;
        this.delivery = delivery;
    }

    @Override
    public void send(String email) {
        String key = email.trim().toLowerCase();
        Entry current = cache.get(key);
        if (current != null && !current.expired() && current.cooldownSeconds() > 0)
            throw new OtpCooldownException("Please wait before requesting another OTP", current.cooldownSeconds());
        String otp = String.format("%06d", RANDOM.nextInt(1_000_000));
        Entry entry = new Entry(encoder.encode(otp), LocalDateTime.now().plusMinutes(expiryMinutes), LocalDateTime.now(), configuredCooldownSeconds);
        java.util.concurrent.atomic.AtomicLong blockedFor = new java.util.concurrent.atomic.AtomicLong();
        cache.compute(key, (ignored, currentEntry) -> {
            long remaining = currentEntry == null || currentEntry.expired() ? 0 : currentEntry.cooldownSeconds();
            if (remaining > 0) { blockedFor.set(remaining); return currentEntry; }
            return entry;
        });
        if (blockedFor.get() > 0) throw new OtpCooldownException("Please wait before requesting another OTP", blockedFor.get());
        try {
            delivery.send(key, "Your EBS BioCrop login code", "Your one-time login code is " + otp + ". It expires in " + expiryMinutes + " minutes.");
        } catch (RuntimeException exception) {
            cache.remove(key, entry);
            throw exception;
        }
    }

    @Override
    public void verify(String email, String otp) {
        String key = email.trim().toLowerCase();
        Entry entry = cache.get(key);
        if (entry == null || entry.expired()) {
            cache.remove(key);
            throw new InvalidOtpException("No active email OTP request found. Please request a new code.");
        }
        synchronized (entry) {
            if (cache.get(key) != entry) throw new InvalidOtpException("The OTP request changed. Please request a new code.");
            if (++entry.attempts > maxAttempts) {
                cache.remove(key, entry);
                throw new InvalidOtpException("Maximum verification attempts exceeded. Please request a new OTP.");
            }
            if (!encoder.matches(otp.trim(), entry.hash)) {
                if (entry.attempts >= maxAttempts) cache.remove(key, entry);
                throw new InvalidOtpException("Invalid email OTP.");
            }
            cache.remove(key, entry);
        }
    }

    private static class Entry {
        final String hash; final LocalDateTime expiresAt; final LocalDateTime createdAt; final int cooldown; int attempts;
        Entry(String hash, LocalDateTime expiresAt, LocalDateTime createdAt, int cooldown) { this.hash=hash; this.expiresAt=expiresAt; this.createdAt=createdAt; this.cooldown=cooldown; }
        boolean expired() { return LocalDateTime.now().isAfter(expiresAt); }
        long cooldownSeconds() { long elapsed=Duration.between(createdAt, LocalDateTime.now()).getSeconds(); return elapsed < cooldown ? Math.max(1,cooldown-elapsed) : 0; }
    }
}
