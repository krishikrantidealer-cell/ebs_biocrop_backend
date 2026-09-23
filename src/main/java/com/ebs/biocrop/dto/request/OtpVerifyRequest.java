package com.ebs.biocrop.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class OtpVerifyRequest {

    @NotBlank(message = "Phone number cannot be blank")
    @Pattern(regexp = "^[0-9]{10}$", message = "Phone number must be a valid 10-digit number")
    private String phoneNumber;

    @NotBlank(message = "OTP cannot be blank")
    @Pattern(regexp = "^[0-9]{6}$", message = "OTP must be a valid 6-digit numeric code")
    private String otp;

    private String role;

    public OtpVerifyRequest() {
    }

    public OtpVerifyRequest(String phoneNumber, String otp) {
        this.phoneNumber = phoneNumber;
        this.otp = otp;
    }

    public OtpVerifyRequest(String phoneNumber, String otp, String role) {
        this.phoneNumber = phoneNumber;
        this.otp = otp;
        this.role = role;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber != null ? phoneNumber.trim() : null;
    }

    public String getOtp() {
        return otp;
    }

    public void setOtp(String otp) {
        this.otp = otp != null ? otp.trim() : null;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role != null ? role.trim() : null;
    }
}
