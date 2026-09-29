package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.request.OtpSendRequest;
import com.ebs.biocrop.dto.request.OtpVerifyRequest;
import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.AuthResponse;
import com.ebs.biocrop.dto.response.OtpResponse;
import com.ebs.biocrop.exception.RateLimitExceededException;
import com.ebs.biocrop.service.AuthService;
import com.ebs.biocrop.service.OtpService;
import com.ebs.biocrop.service.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final OtpService otpService;
    private final AuthService authService;
    private final RateLimitService rateLimitService;


    public AuthController(OtpService otpService, AuthService authService, RateLimitService rateLimitService) {
        this.otpService = otpService;
        this.authService = authService;
        this.rateLimitService = rateLimitService;
    }


    private void checkLimit(String policy, String identifier, int maxRequests, long windowSeconds) {
        long retryAfter = rateLimitService.check(
                policy,
                identifier,
                maxRequests,
                java.time.Duration.ofSeconds(windowSeconds)
        );

        if (retryAfter > 0) {
            throw new RateLimitExceededException(retryAfter);
        }
    }

    @PostMapping("/otp/send")
    public ResponseEntity<ApiResponse<OtpResponse>> sendOtp(
            @Valid @RequestBody OtpSendRequest request,
            HttpServletRequest httpRequest) {

        checkLimit("otp-send-phone", request.getPhoneNumber(), 3, 900);
        checkLimit("otp-send-ip", httpRequest.getRemoteAddr(), 20, 900);

        OtpResponse otpResponse = otpService.generateAndSendOtp(request);
        return ResponseEntity.ok(ApiResponse.ok("OTP dispatched successfully", otpResponse));
    }

    @PostMapping("/otp/verify")
    public ResponseEntity<ApiResponse<AuthResponse>> verifyOtp(
            @Valid @RequestBody OtpVerifyRequest request,
            HttpServletRequest httpRequest) {

        checkLimit("otp-verify-phone", request.getPhoneNumber(), 5, 300);
        checkLimit("otp-verify-ip", httpRequest.getRemoteAddr(), 30, 300);

        AuthResponse authResponse = authService.verifyOtpAndLogin(request);
        return ResponseEntity.ok(ApiResponse.ok("Authentication successful", authResponse));
    }

    @PostMapping("/token/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refreshAccessToken(
            @RequestBody Map<String, String> request,
            HttpServletRequest httpRequest) {
        checkLimit("token-refresh-ip", httpRequest.getRemoteAddr(), 10, 60);
        AuthResponse authResponse = authService.refreshAccessToken(request.get("refreshToken"));
        return ResponseEntity.ok(ApiResponse.ok("Access token refreshed successfully", authResponse));
    }

}
