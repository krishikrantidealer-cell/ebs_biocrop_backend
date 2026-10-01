package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.dto.request.OtpVerifyRequest;
import com.ebs.biocrop.dto.response.AuthResponse;
import com.ebs.biocrop.entity.User;
import com.ebs.biocrop.entity.enums.UserRole;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.repository.UserRepository;
import com.ebs.biocrop.security.jwt.JwtTokenProvider;
import com.ebs.biocrop.service.AuthService;
import com.ebs.biocrop.service.OtpService;
import com.ebs.biocrop.service.EmailOtpService;
import com.ebs.biocrop.service.EmailDeliveryService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Value;
import java.security.SecureRandom;
import java.util.Base64;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    private final OtpService otpService;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final EmailOtpService emailOtpService;
    private final EmailDeliveryService emailDeliveryService;
    private final PasswordEncoder passwordEncoder;
    @Value("${app.password-reset.url:http://localhost:3000/reset-password}") private String resetUrl;
    @Value("${app.password-reset.expiration-minutes:30}") private int resetExpirationMinutes;

    public AuthServiceImpl(
            OtpService otpService,
            UserRepository userRepository,
            JwtTokenProvider jwtTokenProvider,
            EmailOtpService emailOtpService,
            EmailDeliveryService emailDeliveryService,
            PasswordEncoder passwordEncoder) {
        this.otpService = otpService;
        this.userRepository = userRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.emailOtpService = emailOtpService;
        this.emailDeliveryService = emailDeliveryService;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public AuthResponse verifyOtpAndLogin(OtpVerifyRequest request) {
        String phoneNumber = request.getPhoneNumber().trim();

        // 1. Verify the provided OTP (checked against in-memory store)
        otpService.verifyOtp(phoneNumber, request.getOtp());

        // Public OTP verification must never grant or change privileged roles.
        UserRole defaultRole = UserRole.ROLE_CUSTOMER;

        User user = userRepository.findByPhoneNumber(phoneNumber).map(existingUser -> {
            if (Boolean.TRUE.equals(existingUser.getIsDeleted()) || Boolean.TRUE.equals(existingUser.getIsBlocked())) {
                log.warn("Login rejected for inactive account with phone [{}].", maskPhoneNumber(phoneNumber));
                throw new AppException("This account has been deactivated, deleted, or blocked. Please contact support.", HttpStatus.FORBIDDEN);
            }
            if (existingUser.getRole() == UserRole.ROLE_ADMIN) {
                throw new AppException("Admin accounts must authenticate with email OTP or email and password.", HttpStatus.FORBIDDEN);
            }
            return existingUser;
        }).orElseGet(() -> {
            log.info("First-time login: Auto-registering user with phone [{}] and role [{}]", maskPhoneNumber(phoneNumber), defaultRole);
            return new User(phoneNumber, defaultRole);
        });

        boolean isNewUser = user.getId() == null;
        boolean wasProfileComplete = Boolean.TRUE.equals(user.getIsProfileComplete());
        boolean wasVerified = Boolean.TRUE.equals(user.getIsVerified());
        user.setIsProfileComplete(user.hasCompleteProfile());
        // Phone verification and profile completion are separate onboarding states.
        user.setIsVerified(true);
        if (isNewUser || wasProfileComplete != Boolean.TRUE.equals(user.getIsProfileComplete()) || !wasVerified) {
            user.setUpdatedAt(LocalDateTime.now());
            user = userRepository.save(user);
        }

        // 3. Issue JWT Access & Refresh Tokens
        return issueTokens(user);
    }

    @Override
    public AuthResponse refreshAccessToken(String refreshToken) {
        if (refreshToken == null || !jwtTokenProvider.validateToken(refreshToken)
                || !jwtTokenProvider.isRefreshToken(refreshToken)) {
            throw new AppException("Invalid or expired refresh token", HttpStatus.UNAUTHORIZED);
        }

        String identifier = jwtTokenProvider.getPhoneNumberFromToken(refreshToken);
        User user = findByIdentifier(identifier)
                .orElseThrow(() -> new AppException("User account is unavailable", HttpStatus.UNAUTHORIZED));
        if (Boolean.TRUE.equals(user.getIsDeleted()) || Boolean.TRUE.equals(user.getIsBlocked())) {
            throw new AppException("User account is unavailable", HttpStatus.UNAUTHORIZED);
        }

        String roleName = user.getRole() != null ? user.getRole().name() : UserRole.ROLE_CUSTOMER.name();
        String accessToken = jwtTokenProvider.generateAccessToken(loginIdentifier(user), user.getId(), roleName);

        // Keep the original refresh token so refreshes do not extend its original lifetime.
        return new AuthResponse(
                accessToken,
                refreshToken,
                jwtTokenProvider.getExpirationMs(),
                user.getId(),
                user.getPhoneNumber(),
                roleName,
                Boolean.TRUE.equals(user.getIsProfileComplete())
        );
    }

    @Override
    public void assertPhoneOtpAllowed(String phoneNumber) {
        userRepository.findByPhoneNumber(phoneNumber.trim()).ifPresent(user -> {
            if (user.getRole() == UserRole.ROLE_ADMIN)
                throw new AppException("Admin accounts must authenticate with email OTP or email and password.", HttpStatus.FORBIDDEN);
        });
    }

    @Override
    public AuthResponse verifyEmailOtpAndLogin(String email, String otp) {
        emailOtpService.verify(email, otp);
        User user = userRepository.findByEmailIgnoreCase(email.trim().toLowerCase())
                .orElseThrow(() -> new AppException("Account is unavailable", HttpStatus.UNAUTHORIZED));
        ensurePrivilegedLoginAllowed(user);
        return issueTokens(user);
    }

    @Override
    public void sendEmailOtp(String email) {
        userRepository.findByEmailIgnoreCase(email.trim().toLowerCase()).filter(this::isPrivileged)
                .filter(user -> !Boolean.TRUE.equals(user.getIsDeleted()) && !Boolean.TRUE.equals(user.getIsBlocked()))
                .ifPresent(user -> emailOtpService.send(user.getEmail()));
    }

    @Override
    public AuthResponse loginWithPassword(String email, String password) {
        User user = userRepository.findByEmailIgnoreCase(email.trim().toLowerCase())
                .orElseThrow(() -> new AppException("Invalid email or password", HttpStatus.UNAUTHORIZED));
        ensurePrivilegedLoginAllowed(user);
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72
                || user.getPasswordHash() == null || !passwordEncoder.matches(password, user.getPasswordHash()))
            throw new AppException("Invalid email or password", HttpStatus.UNAUTHORIZED);
        return issueTokens(user);
    }

    @Override
    public void setupPassword(String identifier, String newPassword) {
        validatePasswordByteLength(newPassword);
        User user = findByIdentifier(identifier).orElseThrow(() -> new AppException("Account is unavailable", HttpStatus.UNAUTHORIZED));
        ensurePrivilegedLoginAllowed(user);
        if (user.getEmail() == null || user.getEmail().isBlank())
            throw new AppException("An email address is required before setting a password", HttpStatus.CONFLICT);
        if (user.getPasswordHash() != null)
            throw new AppException("Password is already set. Use the forgot password flow to change it.", HttpStatus.CONFLICT);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
    }

    @Override
    public void sendPasswordReset(String email) {
        userRepository.findByEmailIgnoreCase(email.trim().toLowerCase()).filter(this::isPrivileged)
                .filter(user -> !Boolean.TRUE.equals(user.getIsDeleted()) && !Boolean.TRUE.equals(user.getIsBlocked()))
                .ifPresent(user -> {
                    String token = randomToken();
                    user.setPasswordResetTokenHash(sha256(token));
                    user.setPasswordResetExpiresAt(LocalDateTime.now().plusMinutes(resetExpirationMinutes));
                    user.setUpdatedAt(LocalDateTime.now());
                    userRepository.save(user);
                    try {
                        emailDeliveryService.send(user.getEmail(), "Reset your EBS BioCrop password",
                                "Use this one-time link to reset your password: " + resetUrl + "?token=" + token +
                                        "\nThis link expires in " + resetExpirationMinutes + " minutes.");
                    } catch (RuntimeException exception) {
                        user.setPasswordResetTokenHash(null);
                        user.setPasswordResetExpiresAt(null);
                        userRepository.save(user);
                        throw exception;
                    }
                });
    }

    @Override
    public void resetPassword(String token, String newPassword) {
        validatePasswordByteLength(newPassword);
        String tokenHash = sha256(token);
        User user = userRepository.findByPasswordResetTokenHash(tokenHash)
                .orElseThrow(() -> new AppException("Invalid or expired password reset link", HttpStatus.BAD_REQUEST));
        if (!isPrivileged(user) || user.getPasswordResetExpiresAt() == null || user.getPasswordResetExpiresAt().isBefore(LocalDateTime.now()))
            throw new AppException("Invalid or expired password reset link", HttpStatus.BAD_REQUEST);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordResetTokenHash(null);
        user.setPasswordResetExpiresAt(null);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
    }

    private AuthResponse issueTokens(User user) {
        String roleName = user.getRole() != null ? user.getRole().name() : UserRole.ROLE_CUSTOMER.name();
        String identifier = loginIdentifier(user);
        String accessToken = jwtTokenProvider.generateAccessToken(identifier, user.getId(), roleName);
        String refreshToken = jwtTokenProvider.generateRefreshToken(identifier, user.getId());
        log.info("Account authenticated with role [{}]", roleName);
        return new AuthResponse(accessToken, refreshToken, jwtTokenProvider.getExpirationMs(), user.getId(),
                user.getPhoneNumber(), roleName, Boolean.TRUE.equals(user.getIsProfileComplete()));
    }

    private java.util.Optional<User> findByIdentifier(String identifier) {
        if (identifier != null && identifier.contains("@")) return userRepository.findByEmailIgnoreCase(identifier);
        return userRepository.findByPhoneNumber(identifier).or(() -> userRepository.findByEmailIgnoreCase(identifier));
    }

    private String loginIdentifier(User user) {
        return user.getEmail() != null && (user.getPhoneNumber() == null || user.getPhoneNumber().isBlank())
                ? user.getEmail() : user.getPhoneNumber();
    }

    private boolean isPrivileged(User user) {
        return user.getRole() == UserRole.ROLE_ADMIN || user.getRole() == UserRole.ROLE_SELLER;
    }

    private void ensurePrivilegedLoginAllowed(User user) {
        if (!isPrivileged(user) || Boolean.TRUE.equals(user.getIsDeleted()) || Boolean.TRUE.equals(user.getIsBlocked()))
            throw new AppException("Invalid email or password", HttpStatus.UNAUTHORIZED);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void validatePasswordByteLength(String password) {
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new AppException("Password must not exceed 72 UTF-8 bytes", HttpStatus.BAD_REQUEST);
    }

    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private String maskPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.length() < 4) {
            return "****";
        }
        int length = phoneNumber.length();
        return phoneNumber.substring(0, 2) + "*".repeat(length - 4) + phoneNumber.substring(length - 2);
    }

}
