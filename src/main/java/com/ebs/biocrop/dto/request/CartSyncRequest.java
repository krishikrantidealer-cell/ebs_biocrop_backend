package com.ebs.biocrop.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public class CartSyncRequest {

    @NotEmpty(message = "Items list cannot be empty for batch synchronization")
    @Valid
    private List<CartItemRequest> items;

    public CartSyncRequest() {
    }

    public CartSyncRequest(List<CartItemRequest> items) {
        this.items = items;
    }

    public List<CartItemRequest> getItems() {
        return items;
    }

    public void setItems(List<CartItemRequest> items) {
        this.items = items;
    }
}
