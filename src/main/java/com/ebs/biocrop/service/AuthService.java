package com.ebs.biocrop.service;

import com.ebs.biocrop.dto.request.OtpVerifyRequest;
import com.ebs.biocrop.dto.response.AuthResponse;
import com.ebs.biocrop.entity.User;

public interface AuthService {

    AuthResponse verifyOtpAndLogin(OtpVerifyRequest request);

    void assertPhoneOtpAllowed(String phoneNumber);

    AuthResponse refreshAccessToken(String refreshToken);

    AuthResponse verifyEmailOtpAndLogin(String email, String otp);

    void sendEmailOtp(String email);

    AuthResponse loginWithPassword(String email, String password);

    void setupPassword(String identifier, String newPassword);

    void sendPasswordReset(String email);

    void resetPassword(String token, String newPassword);
}
