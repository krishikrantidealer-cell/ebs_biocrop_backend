package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.request.OtpSendRequest;
import com.ebs.biocrop.dto.request.OtpVerifyRequest;
import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.dto.response.AuthResponse;
import com.ebs.biocrop.dto.response.OtpResponse;
import com.ebs.biocrop.service.AuthService;
import com.ebs.biocrop.service.OtpService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final OtpService otpService;
    private final AuthService authService;

    public AuthController(OtpService otpService, AuthService authService) {
        this.otpService = otpService;
        this.authService = authService;
    }

    @PostMapping("/otp/send")
    public ResponseEntity<ApiResponse<OtpResponse>> sendOtp(@Valid @RequestBody OtpSendRequest request) {
        OtpResponse otpResponse = otpService.generateAndSendOtp(request);
        return ResponseEntity.ok(ApiResponse.ok("OTP dispatched successfully", otpResponse));
    }

    @PostMapping("/otp/verify")
    public ResponseEntity<ApiResponse<AuthResponse>> verifyOtp(@Valid @RequestBody OtpVerifyRequest request) {
        AuthResponse authResponse = authService.verifyOtpAndLogin(request);
        return ResponseEntity.ok(ApiResponse.ok("Authentication successful", authResponse));
    }

    @PostMapping("/token/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refreshAccessToken(
            @RequestBody Map<String, String> request) {
        AuthResponse authResponse = authService.refreshAccessToken(request.get("refreshToken"));
        return ResponseEntity.ok(ApiResponse.ok("Access token refreshed successfully", authResponse));
    }
}
