package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.exception.InvalidOtpException;
import com.ebs.biocrop.exception.OtpCooldownException;
import com.ebs.biocrop.service.EmailDeliveryService;
import com.ebs.biocrop.service.EmailOtpService;
import com.ebs.biocrop.service.RedisOtpCooldown;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

@Service
public class EmailOtpServiceImpl implements EmailOtpService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();
    private final PasswordEncoder encoder;
    private final EmailDeliveryService delivery;
    private final RedisOtpCooldown redisOtpCooldown;
    @Value("${app.otp.expiration-minutes:5}") private int expiryMinutes;
    @Value("${app.otp.max-attempts:3}") private int maxAttempts;
    @Value("${app.otp.cooldown-seconds:60}") private int configuredCooldownSeconds;

    public EmailOtpServiceImpl(PasswordEncoder encoder, EmailDeliveryService delivery,
                               RedisOtpCooldown redisOtpCooldown) {
        this.encoder = encoder;
        this.delivery = delivery;
        this.redisOtpCooldown = redisOtpCooldown;
    }

    @Override
    public void send(String email) {
        String key = email.trim().toLowerCase();
        String reservationToken = UUID.randomUUID().toString();
        long remainingCooldown = redisOtpCooldown.acquire("email-send", key,
                java.time.Duration.ofSeconds(configuredCooldownSeconds), reservationToken);
        if (remainingCooldown > 0)
            throw new OtpCooldownException("Please wait before requesting another OTP", remainingCooldown);

        Entry entry = null;
        try {
            String otp = String.format("%06d", RANDOM.nextInt(1_000_000));
            entry = new Entry(encoder.encode(otp), LocalDateTime.now().plusMinutes(expiryMinutes));
            cache.put(key, entry);
            delivery.send(key, "Your EBS BioCrop login code", "Your one-time login code is " + otp + ". It expires in " + expiryMinutes + " minutes.");
        } catch (RuntimeException exception) {
            if (entry != null) cache.remove(key, entry);
            redisOtpCooldown.release("email-send", key, reservationToken);
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
        final String hash; final LocalDateTime expiresAt; int attempts;
        Entry(String hash, LocalDateTime expiresAt) { this.hash=hash; this.expiresAt=expiresAt; }
        boolean expired() { return LocalDateTime.now().isAfter(expiresAt); }
    }
}
