package com.ebs.biocrop.dto.response;

import com.ebs.biocrop.entity.Cart;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class CartResponse {

    private String id;
    private String userId;
    private String phoneNumber;
    private List<CartItemResponse> items = new ArrayList<>();
    private Integer itemCount;
    private Integer totalQuantity;
    private Double totalOriginalPrice;
    private Double totalDiscount;
    private Double totalSalePrice;
    private Double totalCourierCharge;
    private Double finalAmount;
    private LocalDateTime updatedAt;

    public CartResponse() {
    }

    public CartResponse(String id, String userId, String phoneNumber, List<CartItemResponse> items,
                        Integer itemCount, Integer totalQuantity, Double totalOriginalPrice,
                        Double totalDiscount, Double totalSalePrice, Double totalCourierCharge,
                        Double finalAmount, LocalDateTime updatedAt) {
        this.id = id;
        this.userId = userId;
        this.phoneNumber = phoneNumber;
        this.items = items != null ? items : new ArrayList<>();
        this.itemCount = itemCount;
        this.totalQuantity = totalQuantity;
        this.totalOriginalPrice = totalOriginalPrice;
        this.totalDiscount = totalDiscount;
        this.totalSalePrice = totalSalePrice;
        this.totalCourierCharge = totalCourierCharge;
        this.finalAmount = finalAmount;
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
                cart.getUserId(),
                cart.getPhoneNumber(),
                itemResponses,
                itemResponses.size(),
                cart.getTotalQuantity() != null ? cart.getTotalQuantity() : 0,
                cart.getTotalOriginalPrice() != null ? cart.getTotalOriginalPrice() : 0.0,
                cart.getTotalDiscount() != null ? cart.getTotalDiscount() : 0.0,
                cart.getTotalSalePrice() != null ? cart.getTotalSalePrice() : 0.0,
                cart.getTotalCourierCharge() != null ? cart.getTotalCourierCharge() : 0.0,
                cart.getFinalAmount() != null ? cart.getFinalAmount() : 0.0,
                cart.getUpdatedAt()
        );
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public List<CartItemResponse> getItems() {
        return items;
    }

    public void setItems(List<CartItemResponse> items) {
        this.items = items;
    }

    public Integer getItemCount() {
        return itemCount;
    }

    public void setItemCount(Integer itemCount) {
        this.itemCount = itemCount;
    }

    public Integer getTotalQuantity() {
        return totalQuantity;
    }

    public void setTotalQuantity(Integer totalQuantity) {
        this.totalQuantity = totalQuantity;
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

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
