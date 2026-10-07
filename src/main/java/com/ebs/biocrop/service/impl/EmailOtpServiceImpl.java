package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.exception.InvalidOtpException;
import com.ebs.biocrop.exception.OtpCooldownException;
import com.ebs.biocrop.service.EmailDeliveryService;
import com.ebs.biocrop.service.EmailOtpService;
import com.ebs.biocrop.service.RedisOtpCooldown;
import com.ebs.biocrop.service.RedisOtpStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;

import java.security.SecureRandom;
import java.util.UUID;

@Service
public class EmailOtpServiceImpl implements EmailOtpService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final PasswordEncoder encoder;
    private final EmailDeliveryService delivery;
    private final RedisOtpCooldown redisOtpCooldown;
    private final RedisOtpStore redisOtpStore;
    @Value("${app.otp.expiration-minutes:5}") private int expiryMinutes;
    @Value("${app.otp.max-attempts:3}") private int maxAttempts;
    @Value("${app.otp.cooldown-seconds:60}") private int configuredCooldownSeconds;

    public EmailOtpServiceImpl(PasswordEncoder encoder, EmailDeliveryService delivery,
                               RedisOtpCooldown redisOtpCooldown, RedisOtpStore redisOtpStore) {
        this.encoder = encoder;
        this.delivery = delivery;
        this.redisOtpCooldown = redisOtpCooldown;
        this.redisOtpStore = redisOtpStore;
    }

    @Override
    public void send(String email) {
        String key = email.trim().toLowerCase();
        String reservationToken = UUID.randomUUID().toString();
        long remainingCooldown = redisOtpCooldown.acquire("email-send", key,
                java.time.Duration.ofSeconds(configuredCooldownSeconds), reservationToken);
        if (remainingCooldown > 0)
            throw new OtpCooldownException("Please wait before requesting another OTP", remainingCooldown);

        String entryId = null;
        try {
            String otp = String.format("%06d", RANDOM.nextInt(1_000_000));
            entryId = redisOtpStore.save("email", key, encoder.encode(otp), java.time.Duration.ofMinutes(expiryMinutes));
            delivery.send(key, "Your EBS BioCrop login code", "Your one-time login code is " + otp + ". It expires in " + expiryMinutes + " minutes.");
        } catch (RuntimeException exception) {
            if (entryId != null) {
                try {
                    redisOtpStore.deleteIfCurrent("email", key, entryId);
                } catch (RuntimeException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            redisOtpCooldown.release("email-send", key, reservationToken);
            throw exception;
        }
    }

    @Override
    public void verify(String email, String otp) {
        String key = email.trim().toLowerCase(java.util.Locale.ROOT);
        RedisOtpStore.Verification verification = redisOtpStore.beginVerification("email", key, maxAttempts);
        if (encoder.matches(otp.trim(), verification.encodedOtp())) {
            redisOtpStore.complete("email", key, verification);
            return;
        }
        int attempts = redisOtpStore.reject("email", key, verification, maxAttempts);
        int remaining = maxAttempts - attempts;
        if (remaining <= 0) throw new InvalidOtpException("Maximum verification attempts exceeded. Please request a new OTP.");
        throw new InvalidOtpException("Invalid email OTP. " + remaining + " attempt(s) remaining.");
    }
}
