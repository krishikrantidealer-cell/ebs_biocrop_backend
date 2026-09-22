package com.ebs.biocrop.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class UserProfileUpdateRequest {

    @NotBlank(message = "Full Name cannot be blank")
    @Size(min = 2, max = 100, message = "Full Name must be between 2 and 100 characters")
    private String fullName;

    private com.ebs.biocrop.entity.Address address;

    public UserProfileUpdateRequest() {
    }

    public UserProfileUpdateRequest(String fullName, com.ebs.biocrop.entity.Address address) {
        this.fullName = fullName;
        this.address = address;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName != null ? fullName.trim() : null;
    }

    public com.ebs.biocrop.entity.Address getAddress() {
        return address;
    }

    public void setAddress(com.ebs.biocrop.entity.Address address) {
        this.address = address;
    }
}
