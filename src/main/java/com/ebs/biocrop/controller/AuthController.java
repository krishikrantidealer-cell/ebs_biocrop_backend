package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.request.OtpSendRequest;
import com.ebs.biocrop.dto.request.OtpVerifyRequest;
import com.ebs.biocrop.dto.request.EmailOtpRequest;
import com.ebs.biocrop.dto.request.EmailOtpVerifyRequest;
import com.ebs.biocrop.dto.request.EmailPasswordRequest;
import com.ebs.biocrop.dto.request.PasswordSetupRequest;
import com.ebs.biocrop.dto.request.ForgotPasswordRequest;
import com.ebs.biocrop.dto.request.ResetPasswordRequest;
import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.AuthResponse;
import com.ebs.biocrop.dto.response.OtpResponse;
import com.ebs.biocrop.exception.RateLimitExceededException;
import com.ebs.biocrop.service.AuthService;
import com.ebs.biocrop.service.OtpService;
import com.ebs.biocrop.service.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import org.springframework.security.core.Authentication;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Customer, seller, and admin authentication endpoints.")
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
        authService.assertPhoneOtpAllowed(request.getPhoneNumber());

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

    @PostMapping("/email/otp/send")
    @Operation(summary = "Send privileged account email OTP", description = "Sends an OTP only to a provisioned seller or admin account. Customer email login is not supported.")
    public ResponseEntity<ApiResponse<Map<String, String>>> sendEmailOtp(@Valid @RequestBody EmailOtpRequest request, HttpServletRequest httpRequest) {
        checkLimit("email-otp-send", request.getEmail(), 3, 900);
        checkLimit("email-otp-send-ip", httpRequest.getRemoteAddr(), 20, 900);
        authService.sendEmailOtp(request.getEmail());
        return ResponseEntity.ok(ApiResponse.ok("If this email belongs to an eligible account, an OTP has been sent.", Map.of("status", "OTP_REQUESTED")));
    }

    @PostMapping("/email/otp/verify")
    @Operation(summary = "Verify seller or admin email OTP")
    public ResponseEntity<ApiResponse<AuthResponse>> verifyEmailOtp(@Valid @RequestBody EmailOtpVerifyRequest request, HttpServletRequest httpRequest) {
        checkLimit("email-otp-verify", request.getEmail(), 5, 300);
        checkLimit("email-otp-verify-ip", httpRequest.getRemoteAddr(), 30, 300);
        return ResponseEntity.ok(ApiResponse.ok("Authentication successful", authService.verifyEmailOtpAndLogin(request.getEmail(), request.getOtp())));
    }

    @PostMapping("/password/login")
    @Operation(summary = "Login seller or admin with email and password")
    public ResponseEntity<ApiResponse<AuthResponse>> passwordLogin(@Valid @RequestBody EmailPasswordRequest request, HttpServletRequest httpRequest) {
        checkLimit("password-login-email", request.getEmail(), 5, 300);
        checkLimit("password-login-ip", httpRequest.getRemoteAddr(), 30, 300);
        return ResponseEntity.ok(ApiResponse.ok("Authentication successful", authService.loginWithPassword(request.getEmail(), request.getPassword())));
    }

    @PostMapping("/password/setup")
    @Operation(summary = "Set password after OTP login")
    public ResponseEntity<ApiResponse<Void>> setupPassword(@Valid @RequestBody PasswordSetupRequest request, Authentication authentication) {
        authService.setupPassword(authentication.getName(), request.getNewPassword());
        return ResponseEntity.ok(ApiResponse.ok("Password set successfully", null));
    }

    @PostMapping("/password/forgot")
    @Operation(summary = "Request a password reset email")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest httpRequest) {
        checkLimit("password-reset-email", request.getEmail(), 3, 900);
        checkLimit("password-reset-ip", httpRequest.getRemoteAddr(), 20, 900);
        authService.sendPasswordReset(request.getEmail());
        return ResponseEntity.ok(ApiResponse.ok("If this email belongs to an eligible account, a reset link has been sent.", null));
    }

    @PostMapping("/password/reset")
    @Operation(summary = "Reset password using one-time email link")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequest request, HttpServletRequest httpRequest) {
        checkLimit("password-reset-complete-ip", httpRequest.getRemoteAddr(), 20, 900);
        authService.resetPassword(request.getToken(), request.getNewPassword());
        return ResponseEntity.ok(ApiResponse.ok("Password reset successfully", null));
    }

}
