package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.dto.request.OtpSendRequest;
import com.ebs.biocrop.dto.response.OtpResponse;
import com.ebs.biocrop.exception.InvalidOtpException;
import com.ebs.biocrop.exception.OtpCooldownException;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.service.OtpService;
import com.ebs.biocrop.service.RedisOtpCooldown;
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
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.UUID;

@Service
public class OtpServiceImpl implements OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpServiceImpl.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // Zero database storage: Thread-safe in-memory cache for high speed & zero extra cloud cost
    private final ConcurrentMap<String, OtpEntry> otpCache = new ConcurrentHashMap<>();
    private final PasswordEncoder passwordEncoder;
    private final Environment environment;
    private final RedisOtpCooldown redisOtpCooldown;

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
                          RedisOtpCooldown redisOtpCooldown) {
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
        this.redisOtpCooldown = redisOtpCooldown;
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

        OtpEntry candidate = null;
        try {
            String plainOtp = generateNumericOtp(otpLength);
            String hashedOtp = passwordEncoder.encode(plainOtp);
            LocalDateTime expiryTime = LocalDateTime.now().plusMinutes(expirationMinutes);
            candidate = new OtpEntry(hashedOtp, expiryTime);
            otpCache.put(phoneNumber, candidate);

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
            if (candidate != null) otpCache.remove(phoneNumber, candidate);
            redisOtpCooldown.release("phone-send", phoneNumber, reservationToken);
            throw exception;
        }
    }

    @Override
    public void verifyOtp(String phoneNumber, String rawOtp) {
        String trimmedPhoneNumber = phoneNumber.trim();

        OtpEntry otpEntry = otpCache.get(trimmedPhoneNumber);
        if (otpEntry == null) {
            throw new InvalidOtpException("No active OTP request found for phone number: " + trimmedPhoneNumber);
        }

        synchronized (otpEntry) {
            if (otpCache.get(trimmedPhoneNumber) != otpEntry) {
                throw new InvalidOtpException("The OTP request changed. Please request a new OTP.");
            }

            if (otpEntry.isExpired()) {
                otpCache.remove(trimmedPhoneNumber, otpEntry);
                throw new InvalidOtpException("The OTP has expired. Please request a new one.");
            }
            if (otpEntry.getAttempts() >= maxAttempts) {
                otpCache.remove(trimmedPhoneNumber, otpEntry);
                throw new InvalidOtpException("Maximum verification attempts exceeded. Please request a new OTP.");
            }

            otpEntry.incrementAttempts();
            boolean matches = passwordEncoder.matches(rawOtp.trim(), otpEntry.getHashedOtp());
            if (!matches) {
                int remaining = maxAttempts - otpEntry.getAttempts();
                if (remaining <= 0) {
                    otpCache.remove(trimmedPhoneNumber, otpEntry);
                    throw new InvalidOtpException("Invalid OTP. Maximum attempts reached. Please request a new OTP.");
                }
                throw new InvalidOtpException("Invalid OTP entered. " + remaining + " attempt(s) remaining.");
            }

            // Remove the consumed entry to prevent replay.
            otpCache.remove(trimmedPhoneNumber, otpEntry);
        }
        log.info("Successfully verified OTP for phone [{}]", maskPhoneNumber(trimmedPhoneNumber));
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

    // In-memory OTP model
    private static class OtpEntry {
        private final String hashedOtp;
        private final LocalDateTime expiryTime;
        private int attempts;

        public OtpEntry(String hashedOtp, LocalDateTime expiryTime) {
            this.hashedOtp = hashedOtp;
            this.expiryTime = expiryTime;
            this.attempts = 0;
        }

        public String getHashedOtp() {
            return hashedOtp;
        }

        public LocalDateTime getExpiryTime() {
            return expiryTime;
        }

        public int getAttempts() {
            return attempts;
        }

        public void incrementAttempts() {
            this.attempts++;
        }

        public boolean isExpired() {
            return LocalDateTime.now().isAfter(this.expiryTime);
        }
    }
}
