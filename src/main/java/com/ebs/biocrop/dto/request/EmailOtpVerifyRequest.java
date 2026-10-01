package com.ebs.biocrop.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class EmailOtpVerifyRequest {
    @NotBlank @Email private String email;
    @NotBlank @Pattern(regexp = "^[0-9]{6}$") private String otp;
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email == null ? null : email.trim().toLowerCase(); }
    public String getOtp() { return otp; }
    public void setOtp(String otp) { this.otp = otp == null ? null : otp.trim(); }
}
