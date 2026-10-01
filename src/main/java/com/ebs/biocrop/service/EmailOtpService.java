package com.ebs.biocrop.service;

public interface EmailOtpService {
    void send(String email);
    void verify(String email, String otp);
}
