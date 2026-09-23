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

        // 2. Lookup or provision user in MongoDB (supports testing all 3 roles: ROLE_CUSTOMER, ROLE_SELLER, ROLE_ADMIN)
        UserRole requestedRole = request.getRole() != null ? UserRole.fromString(request.getRole()) : null;
        UserRole defaultRole = requestedRole != null ? requestedRole : UserRole.ROLE_CUSTOMER;

        User user = userRepository.findByPhoneNumber(phoneNumber).map(existingUser -> {
            if (Boolean.TRUE.equals(existingUser.getIsDelete())) {
                log.warn("Login rejected: Account for phone [{}] is soft-deleted.", phoneNumber);
                throw new AppException("This account has been deactivated or deleted. Please contact support.", HttpStatus.FORBIDDEN);
            }
            if (requestedRole != null && existingUser.getRole() != requestedRole) {
                log.info("Updating existing user [{}] role from [{}] to [{}]", phoneNumber, existingUser.getRole(), requestedRole);
                existingUser.setRole(requestedRole);
                existingUser.setUpdatedAt(java.time.LocalDateTime.now());
                return userRepository.save(existingUser);
            }
            return existingUser;
        }).orElseGet(() -> {
            log.info("First-time login: Auto-registering new user in 'users' for phone: {} with role: {}", phoneNumber, defaultRole);
            User newUser = new User(phoneNumber, defaultRole);
            return userRepository.save(newUser);
        });

        // 3. Issue JWT Access & Refresh Tokens
        String roleName = user.getRole().name();
        String accessToken = jwtTokenProvider.generateAccessToken(user.getPhoneNumber(), user.getId(), roleName);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getPhoneNumber(), user.getId());

        log.info("User [{}] authenticated successfully with role [{}]. Issued JWT token.", user.getPhoneNumber(), roleName);

        return new AuthResponse(
                accessToken,
                refreshToken,
                jwtTokenProvider.getExpirationMs(),
                user.getId(),
                user.getPhoneNumber(),
                roleName
        );
    }
}
