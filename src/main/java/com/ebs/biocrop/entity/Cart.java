package com.ebs.biocrop.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "carts")
public class Cart {

    @Id
    private String id;

    @Indexed(unique = true)
    private String phoneNumber;

    @Indexed
    private String userId;

    private List<CartItem> items = new ArrayList<>();

    private Double totalOriginalPrice = 0.0;
    private Double totalDiscount = 0.0;
    private Double totalSalePrice = 0.0;
    private Double totalCourierCharge = 0.0;
    private Double finalAmount = 0.0;
    private Integer totalQuantity = 0;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Cart() {
        this.items = new ArrayList<>();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Cart(String phoneNumber, String userId) {
        this();
        this.phoneNumber = phoneNumber;
        this.userId = userId;
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

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public List<CartItem> getItems() {
        return items;
    }

    public void setItems(List<CartItem> items) {
        this.items = items != null ? items : new ArrayList<>();
    }

    public Double getTotalOriginalPrice() {
        return totalOriginalPrice;
    }

    public void setTotalOriginalPrice(Double totalOriginalPrice) {
        this.totalOriginalPrice = totalOriginalPrice;
    }

    public Double getTotalDiscount() {
        return totalDiscount;
    }

    public void setTotalDiscount(Double totalDiscount) {
        this.totalDiscount = totalDiscount;
    }

    public Double getTotalSalePrice() {
        return totalSalePrice;
    }

    public void setTotalSalePrice(Double totalSalePrice) {
        this.totalSalePrice = totalSalePrice;
    }

    public Double getTotalCourierCharge() {
        return totalCourierCharge;
    }

    public void setTotalCourierCharge(Double totalCourierCharge) {
        this.totalCourierCharge = totalCourierCharge;
    }

    public Double getFinalAmount() {
        return finalAmount;
    }

    public void setFinalAmount(Double finalAmount) {
        this.finalAmount = finalAmount;
    }

    public Integer getTotalQuantity() {
        return totalQuantity;
    }

    public void setTotalQuantity(Integer totalQuantity) {
        this.totalQuantity = totalQuantity;
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
}
