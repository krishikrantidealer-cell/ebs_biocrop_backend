package com.ebs.biocrop.dto.response;

import java.time.LocalDateTime;

public class UserProfileResponse {

    private String id;
    private String phoneNumber;
    private String fullName;
    private com.ebs.biocrop.entity.Address address;
    private String role;
    @com.fasterxml.jackson.annotation.JsonProperty("is_delete")
    private Boolean isDelete;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public UserProfileResponse() {
    }

    public UserProfileResponse(String id, String phoneNumber, String fullName, com.ebs.biocrop.entity.Address address, String role, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, phoneNumber, fullName, address, role, false, createdAt, updatedAt);
    }

    public UserProfileResponse(String id, String phoneNumber, String fullName, com.ebs.biocrop.entity.Address address, String role, Boolean isDelete, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.phoneNumber = phoneNumber;
        this.fullName = fullName;
        this.address = address;
        this.role = role;
        this.isDelete = isDelete != null ? isDelete : false;
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

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
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

    public Boolean getIsDelete() {
        return isDelete != null ? isDelete : false;
    }

    public void setIsDelete(Boolean isDelete) {
        this.isDelete = isDelete != null ? isDelete : false;
    }
}
