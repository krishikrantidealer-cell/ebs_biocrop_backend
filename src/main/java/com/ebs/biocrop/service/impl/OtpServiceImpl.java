package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.dto.request.OtpSendRequest;
import com.ebs.biocrop.dto.response.OtpResponse;
import com.ebs.biocrop.exception.InvalidOtpException;
import com.ebs.biocrop.exception.OtpCooldownException;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.service.OtpService;
import com.ebs.biocrop.service.RedisOtpCooldown;
import com.ebs.biocrop.service.RedisOtpStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.UUID;

@Service
public class OtpServiceImpl implements OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpServiceImpl.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final PasswordEncoder passwordEncoder;
    private final Environment environment;
    private final RedisOtpCooldown redisOtpCooldown;
    private final RedisOtpStore redisOtpStore;

    @Value("${app.otp.console-delivery.enabled:false}")
    private boolean consoleDeliveryEnabled;

    @Value("${app.otp.length:6}")
    private int otpLength;

    @Value("${app.otp.expiration-minutes:5}")
    private int expirationMinutes;

    @Value("${app.otp.cooldown-seconds:60}")
    private int cooldownSeconds;

    @Value("${app.otp.max-attempts:3}")
    private int maxAttempts;

    public OtpServiceImpl(PasswordEncoder passwordEncoder, Environment environment,
                          RedisOtpCooldown redisOtpCooldown, RedisOtpStore redisOtpStore) {
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
        this.redisOtpCooldown = redisOtpCooldown;
        this.redisOtpStore = redisOtpStore;
    }

    @Override
    public OtpResponse generateAndSendOtp(OtpSendRequest request) {
        String phoneNumber = request.getPhoneNumber().trim();
        boolean devConsoleDelivery = consoleDeliveryEnabled && environment.acceptsProfiles(Profiles.of("dev"));
        if (!devConsoleDelivery) {
            throw new AppException("OTP delivery is not configured. Configure an SMS provider before enabling authentication.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }

        String reservationToken = UUID.randomUUID().toString();
        long remainingCooldown = redisOtpCooldown.acquire("phone-send", phoneNumber,
                Duration.ofSeconds(cooldownSeconds), reservationToken);
        if (remainingCooldown > 0) {
            throw new OtpCooldownException(
                    "Please wait " + remainingCooldown + " seconds before requesting another OTP",
                    remainingCooldown
            );
        }

        String entryId = null;
        try {
            String plainOtp = generateNumericOtp(otpLength);
            String hashedOtp = passwordEncoder.encode(plainOtp);
            entryId = redisOtpStore.save("phone", phoneNumber, hashedOtp, Duration.ofMinutes(expirationMinutes));

            // Console delivery is available only in an explicitly enabled dev profile.
            log.info("Development OTP for phone [{}]: [{}] (expires in {} minutes)",
                    maskPhoneNumber(phoneNumber), plainOtp, expirationMinutes);

            return new OtpResponse(
                    maskPhoneNumber(phoneNumber),
                    "OTP_SENT",
                    cooldownSeconds,
                    expirationMinutes,
                    "OTP has been successfully dispatched."
            );
        } catch (RuntimeException exception) {
            if (entryId != null) {
                try {
                    redisOtpStore.deleteIfCurrent("phone", phoneNumber, entryId);
                } catch (RuntimeException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            redisOtpCooldown.release("phone-send", phoneNumber, reservationToken);
            throw exception;
        }
    }

    @Override
    public void verifyOtp(String phoneNumber, String rawOtp) {
        String normalizedPhone = phoneNumber.trim();
        RedisOtpStore.Verification verification = redisOtpStore.beginVerification("phone", normalizedPhone, maxAttempts);
        if (passwordEncoder.matches(rawOtp.trim(), verification.encodedOtp())) {
            redisOtpStore.complete("phone", normalizedPhone, verification);
            log.info("Successfully verified OTP for phone [{}]", maskPhoneNumber(normalizedPhone));
            return;
        }
        int attempts = redisOtpStore.reject("phone", normalizedPhone, verification, maxAttempts);
        int remaining = maxAttempts - attempts;
        if (remaining <= 0) throw new InvalidOtpException("Invalid OTP. Maximum attempts reached. Please request a new OTP.");
        throw new InvalidOtpException("Invalid OTP entered. " + remaining + " attempt(s) remaining.");
    }

    private String generateNumericOtp(int length) {
        int bound = (int) Math.pow(10, length);
        int floor = (int) Math.pow(10, length - 1);
        int code = floor + SECURE_RANDOM.nextInt(bound - floor);
        return String.valueOf(code);
    }

    private String maskPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.length() < 4) {
            return "****";
        }
        int len = phoneNumber.length();
        return phoneNumber.substring(0, 2) + "*".repeat(len - 4) + phoneNumber.substring(len - 2);
    }
}
