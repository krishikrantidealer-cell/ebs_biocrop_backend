package com.ebs.biocrop.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class CartItemRequest {

    @NotBlank(message = "Variant ID is required")
    @Pattern(regexp = "(?i)^[0-9a-f]{24}$", message = "Variant ID must be a valid MongoDB ObjectId")
    private String variantId;

    @Min(value = 1, message = "Quantity must be at least 1")
    private Integer quantity = 1;

    public CartItemRequest() {}

    public CartItemRequest(String variantId, Integer quantity) {
        this.variantId = variantId;
        this.quantity = quantity != null ? quantity : 1;
    }

    public String getVariantId() {
        return variantId;
    }

    public void setVariantId(String variantId) {
        this.variantId = variantId;
    }

    public Integer getQuantity() {
        return quantity != null ? quantity : 1;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }
}
