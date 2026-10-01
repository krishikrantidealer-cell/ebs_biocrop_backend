package com.ebs.biocrop.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class EmailPasswordRequest {
    @NotBlank @Email private String email;
    @NotBlank @Size(max = 72) private String password;
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email == null ? null : email.trim().toLowerCase(); }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
