package com.ebs.biocrop.dto.response;

import com.ebs.biocrop.entity.CartItem;

public class CartItemResponse {

    private String id;
    private String productId;
    private String variantId;
    private Integer quantity;
    private Double price;

    public CartItemResponse() {
    }

    public CartItemResponse(String id, String productId, String variantId, Integer quantity, Double price) {
        this.id = id;
        this.productId = productId;
        this.variantId = variantId;
        this.quantity = quantity;
        this.price = price;
    }

    public static CartItemResponse fromEntity(CartItem item) {
        if (item == null) return null;
        return new CartItemResponse(
                item.getId(),
                item.getProductId(),
                item.getVariantId(),
                item.getQuantity(),
                item.getPrice()
        );
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getVariantId() {
        return variantId;
    }

    public void setVariantId(String variantId) {
        this.variantId = variantId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public Double getPrice() {
        return price;
    }

    public void setPrice(Double price) {
        this.price = price;
    }
}
