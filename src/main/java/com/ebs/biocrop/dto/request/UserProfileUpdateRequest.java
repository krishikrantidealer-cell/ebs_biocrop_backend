package com.ebs.biocrop.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class UserProfileUpdateRequest {

    @NotBlank(message = "First Name cannot be blank")
    @Size(min = 2, max = 50, message = "First Name must be between 2 and 50 characters")
    private String firstName;

    @NotBlank(message = "Last Name cannot be blank")
    @Size(min = 2, max = 50, message = "Last Name must be between 2 and 50 characters")
    private String lastName;

    @NotNull(message = "Address is required")
    @Valid
    private com.ebs.biocrop.entity.Address address;

    public UserProfileUpdateRequest() {
    }

    public UserProfileUpdateRequest(String firstName, String lastName, com.ebs.biocrop.entity.Address address) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.address = address;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName != null ? firstName.trim() : null;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName != null ? lastName.trim() : null;
    }

    public com.ebs.biocrop.entity.Address getAddress() {
        return address;
    }

    public void setAddress(com.ebs.biocrop.entity.Address address) {
        this.address = address;
    }
}
