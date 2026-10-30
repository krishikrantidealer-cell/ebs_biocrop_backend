package com.ebs.biocrop.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Product IDs for an explicitly requested permanent deletion (single or bulk). */
public record DeleteProductsRequest(
        @NotEmpty(message = "At least one product ID is required")
        @Size(max = 100, message = "At most 100 products can be deleted at once")
        List<@NotBlank(message = "Product IDs cannot be blank") @Size(max = 64) String> productIds) {
}
