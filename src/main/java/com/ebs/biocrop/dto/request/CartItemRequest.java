package com.ebs.biocrop.dto.request;

import jakarta.validation.constraints.Min;

public class CartItemRequest {

    private String variationCode;

    private String productId;

    @Min(value = 1, message = "Quantity must be at least 1")
    private Integer quantity = 1;

    public CartItemRequest() {
    }

    public CartItemRequest(String variationCode, String productId, Integer quantity) {
        this.variationCode = variationCode;
        this.productId = productId;
        this.quantity = quantity != null ? quantity : 1;
    }

    public String getVariationCode() {
        return variationCode;
    }

    public void setVariationCode(String variationCode) {
        this.variationCode = variationCode;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public Integer getQuantity() {
        return quantity != null ? quantity : 1;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }
}
