package com.ebs.biocrop.dto.response;

import java.time.LocalDateTime;

public class UserProfileResponse {

    private String id;
    private String phoneNumber;
    private String firstName;
    private String lastName;
    private com.ebs.biocrop.entity.Address address;
    private String role;
    private Boolean isDeleted;
    private Boolean isProfileComplete;
    private Boolean isVerified;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public UserProfileResponse() {
    }

    public UserProfileResponse(String id, String phoneNumber, String firstName, String lastName, com.ebs.biocrop.entity.Address address, String role, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, phoneNumber, firstName, lastName, address, role, false, createdAt, updatedAt);
    }

    public UserProfileResponse(String id, String phoneNumber, String firstName, String lastName, com.ebs.biocrop.entity.Address address, String role, Boolean isDeleted, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, phoneNumber, firstName, lastName, address, role, isDeleted,
                false, false, createdAt, updatedAt);
    }

    public UserProfileResponse(String id, String phoneNumber, String firstName, String lastName,
                               com.ebs.biocrop.entity.Address address, String role, Boolean isDeleted,
                               Boolean isProfileComplete, Boolean isVerified,
                               LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.phoneNumber = phoneNumber;
        this.firstName = firstName;
        this.lastName = lastName;
        this.address = address;
        this.role = role;
        this.isDeleted = isDeleted != null ? isDeleted : false;
        this.isProfileComplete = isProfileComplete != null ? isProfileComplete : false;
        this.isVerified = isVerified != null ? isVerified : false;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public com.ebs.biocrop.entity.Address getAddress() {
        return address;
    }

    public void setAddress(com.ebs.biocrop.entity.Address address) {
        this.address = address;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Boolean getIsDeleted() {
        return isDeleted != null ? isDeleted : false;
    }

    public void setIsDeleted(Boolean isDeleted) {
        this.isDeleted = isDeleted != null ? isDeleted : false;
    }

    public Boolean getIsProfileComplete() { return isProfileComplete != null && isProfileComplete; }
    public void setIsProfileComplete(Boolean isProfileComplete) { this.isProfileComplete = isProfileComplete != null && isProfileComplete; }

    public Boolean getIsVerified() { return isVerified != null && isVerified; }
    public void setIsVerified(Boolean isVerified) { this.isVerified = isVerified != null && isVerified; }
}
