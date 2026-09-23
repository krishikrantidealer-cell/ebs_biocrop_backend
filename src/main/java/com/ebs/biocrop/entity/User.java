package com.ebs.biocrop.entity;

import com.ebs.biocrop.entity.enums.UserRole;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "users")
public class User {

    @Id
    private String id;

    @Indexed(unique = true)
    private String phoneNumber;

    private String fullName;

    private Address address;

    private UserRole role = UserRole.ROLE_CUSTOMER;

    @org.springframework.data.mongodb.core.mapping.Field("is_delete")
    @com.fasterxml.jackson.annotation.JsonProperty("is_delete")
    private Boolean isDelete = false;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public User() {
        this.isDelete = false;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public User(String phoneNumber, UserRole role) {
        this();
        this.phoneNumber = phoneNumber;
        this.role = role != null ? role : UserRole.ROLE_CUSTOMER;
    }

    public User(String phoneNumber, String fullName, Address address, UserRole role) {
        this();
        this.phoneNumber = phoneNumber;
        this.fullName = fullName;
        this.address = address;
        this.role = role != null ? role : UserRole.ROLE_CUSTOMER;
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

    public Address getAddress() {
        return address;
    }

    public void setAddress(Address address) {
        this.address = address;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role != null ? role : UserRole.ROLE_CUSTOMER;
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
