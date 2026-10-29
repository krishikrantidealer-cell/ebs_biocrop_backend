package com.ebs.biocrop.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class SellerEmailRegistrationSendRequest {
    @NotBlank @Email private String email;
    @NotBlank @Pattern(regexp = "^[0-9]{10}$") private String phoneNumber;
    public String getEmail() { return email; }
    public void setEmail(String value) { email = value == null ? null : value.trim().toLowerCase(java.util.Locale.ROOT); }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String value) { phoneNumber = value == null ? null : value.trim(); }
}
