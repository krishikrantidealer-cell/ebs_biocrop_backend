package com.ebs.biocrop.dto.response;

import com.ebs.biocrop.entity.Cart;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class CartResponse {

    private String id;
    private String user;
    private List<CartItemResponse> items = new ArrayList<>();
    
    private Double totalAmount;
    private Double discountAmount;
    private Double finalAmount;

    private List<String> freeItems = new ArrayList<>();
    private String appliedCoupon;
    private LocalDateTime lastReminderSentAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public CartResponse() {
    }

    public CartResponse(String id, String user, List<CartItemResponse> items,
                        Double totalAmount, Double discountAmount, Double finalAmount,
                        List<String> freeItems, String appliedCoupon, LocalDateTime lastReminderSentAt,
                        LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.user = user;
        this.items = items != null ? items : new ArrayList<>();
        this.totalAmount = totalAmount;
        this.discountAmount = discountAmount;
        this.finalAmount = finalAmount;
        this.freeItems = freeItems != null ? freeItems : new ArrayList<>();
        this.appliedCoupon = appliedCoupon;
        this.lastReminderSentAt = lastReminderSentAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static CartResponse fromEntity(Cart cart) {
        if (cart == null) {
            return null;
        }
        List<CartItemResponse> itemResponses = cart.getItems() != null
                ? cart.getItems().stream().map(CartItemResponse::fromEntity).collect(Collectors.toList())
                : new ArrayList<>();

        return new CartResponse(
                cart.getId(),
                cart.getUser(),
                itemResponses,
                cart.getTotalAmount() != null ? cart.getTotalAmount() : 0.0,
                cart.getDiscountAmount() != null ? cart.getDiscountAmount() : 0.0,
                cart.getFinalAmount() != null ? cart.getFinalAmount() : 0.0,
                cart.getFreeItems(),
                cart.getAppliedCoupon(),
                cart.getLastReminderSentAt(),
                cart.getCreatedAt(),
                cart.getUpdatedAt()
        );
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public List<CartItemResponse> getItems() {
        return items;
    }

    public void setItems(List<CartItemResponse> items) {
        this.items = items;
    }

    public Double getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(Double totalAmount) {
        this.totalAmount = totalAmount;
    }

    public Double getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(Double discountAmount) {
        this.discountAmount = discountAmount;
    }

    public Double getFinalAmount() {
        return finalAmount;
    }

    public void setFinalAmount(Double finalAmount) {
        this.finalAmount = finalAmount;
    }

    public List<String> getFreeItems() {
        return freeItems;
    }

    public void setFreeItems(List<String> freeItems) {
        this.freeItems = freeItems;
    }

    public String getAppliedCoupon() {
        return appliedCoupon;
    }

    public void setAppliedCoupon(String appliedCoupon) {
        this.appliedCoupon = appliedCoupon;
    }

    public LocalDateTime getLastReminderSentAt() {
        return lastReminderSentAt;
    }

    public void setLastReminderSentAt(LocalDateTime lastReminderSentAt) {
        this.lastReminderSentAt = lastReminderSentAt;
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
