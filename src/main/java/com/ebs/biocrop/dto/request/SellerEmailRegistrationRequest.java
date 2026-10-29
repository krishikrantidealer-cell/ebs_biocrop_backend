package com.ebs.biocrop.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Email OTP seller signup still collects a phone number for the users collection's unique identity. */
public class SellerEmailRegistrationRequest {
    @NotBlank @Email private String email;
    @NotBlank @Pattern(regexp = "^[0-9]{10}$") private String phoneNumber;
    @NotBlank @Pattern(regexp = "^[0-9]{6}$") private String otp;

    public String getEmail() { return email; }
    public void setEmail(String value) { email = value == null ? null : value.trim().toLowerCase(java.util.Locale.ROOT); }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String value) { phoneNumber = value == null ? null : value.trim(); }
    public String getOtp() { return otp; }
    public void setOtp(String value) { otp = value == null ? null : value.trim(); }
}
