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

    public AuthServiceImpl(
            OtpService otpService,
            UserRepository userRepository,
            JwtTokenProvider jwtTokenProvider) {
        this.otpService = otpService;
        this.userRepository = userRepository;
        this.jwtTokenProvider = jwtTokenProvider;
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
            return existingUser;
        }).orElseGet(() -> {
            log.info("First-time login: Auto-registering user with phone [{}] and role [{}]", maskPhoneNumber(phoneNumber), defaultRole);
            return new User(phoneNumber, defaultRole);
        });

        boolean isNewUser = user.getId() == null;
        boolean wasProfileComplete = Boolean.TRUE.equals(user.getIsProfileComplete());
        boolean wasVerified = Boolean.TRUE.equals(user.getIsVerified());
        user.setIsProfileComplete(user.hasCompleteProfile());
        // This method runs only after successful OTP verification.
        user.setIsVerified(Boolean.TRUE.equals(user.getIsProfileComplete()));
        if (isNewUser || wasProfileComplete != Boolean.TRUE.equals(user.getIsProfileComplete())
                || wasVerified != Boolean.TRUE.equals(user.getIsVerified())) {
            user.setUpdatedAt(LocalDateTime.now());
            user = userRepository.save(user);
        }

        // 3. Issue JWT Access & Refresh Tokens
        String roleName = user.getRole() != null ? user.getRole().name() : UserRole.ROLE_CUSTOMER.name();
        String accessToken = jwtTokenProvider.generateAccessToken(user.getPhoneNumber(), user.getId(), roleName);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getPhoneNumber(), user.getId());

        log.info("User with phone [{}] authenticated successfully with role [{}]", maskPhoneNumber(user.getPhoneNumber()), roleName);

        return new AuthResponse(
                accessToken,
                refreshToken,
                jwtTokenProvider.getExpirationMs(),
                user.getId(),
                user.getPhoneNumber(),
                roleName
        );
    }

    @Override
    public AuthResponse refreshAccessToken(String refreshToken) {
        if (refreshToken == null || !jwtTokenProvider.validateToken(refreshToken)
                || !jwtTokenProvider.isRefreshToken(refreshToken)) {
            throw new AppException("Invalid or expired refresh token", HttpStatus.UNAUTHORIZED);
        }

        String phoneNumber = jwtTokenProvider.getPhoneNumberFromToken(refreshToken);
        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new AppException("User account is unavailable", HttpStatus.UNAUTHORIZED));
        if (Boolean.TRUE.equals(user.getIsDeleted()) || Boolean.TRUE.equals(user.getIsBlocked())) {
            throw new AppException("User account is unavailable", HttpStatus.UNAUTHORIZED);
        }

        String roleName = user.getRole() != null ? user.getRole().name() : UserRole.ROLE_CUSTOMER.name();
        String accessToken = jwtTokenProvider.generateAccessToken(user.getPhoneNumber(), user.getId(), roleName);

        // Keep the original refresh token so refreshes do not extend its original lifetime.
        return new AuthResponse(
                accessToken,
                refreshToken,
                jwtTokenProvider.getExpirationMs(),
                user.getId(),
                user.getPhoneNumber(),
                roleName
        );
    }

    private String maskPhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.length() < 4) {
            return "****";
        }
        int length = phoneNumber.length();
        return phoneNumber.substring(0, 2) + "*".repeat(length - 4) + phoneNumber.substring(length - 2);
    }

}
