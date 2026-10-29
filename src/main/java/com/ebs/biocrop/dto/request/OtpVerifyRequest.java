package com.ebs.biocrop.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.AssertTrue;

public class OtpVerifyRequest {

    @NotBlank(message = "Phone number cannot be blank")
    @Pattern(regexp = "^[0-9]{10}$", message = "Phone number must be a valid 10-digit number")
    private String phoneNumber;

    @NotBlank(message = "OTP cannot be blank")
    @Pattern(regexp = "^[0-9]{6}$", message = "OTP must be a valid 6-digit numeric code")
    private String otp;

    // Optional role selection applies only when this phone number is registering for the first time.
    private String role;

    public OtpVerifyRequest() {
    }

    public OtpVerifyRequest(String phoneNumber, String otp) {
        this.phoneNumber = phoneNumber;
        this.otp = otp;
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

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role == null ? null : role.trim().toUpperCase(java.util.Locale.ROOT); }

    @AssertTrue(message = "Role must be CUSTOMER or SELLER")
    public boolean isAllowedRegistrationRole() {
        return role == null || role.equals("CUSTOMER") || role.equals("SELLER")
                || role.equals("ROLE_CUSTOMER") || role.equals("ROLE_SELLER");
    }

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object value) {
        throw new IllegalArgumentException("Unsupported OTP verification field: " + fieldName);
    }
}
