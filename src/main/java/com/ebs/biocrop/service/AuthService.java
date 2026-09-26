package com.ebs.biocrop.service;

import com.ebs.biocrop.dto.request.OtpVerifyRequest;
import com.ebs.biocrop.dto.response.AuthResponse;

public interface AuthService {

    AuthResponse verifyOtpAndLogin(OtpVerifyRequest request);

    AuthResponse refreshAccessToken(String refreshToken);
}
