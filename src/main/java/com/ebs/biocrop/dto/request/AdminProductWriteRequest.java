package com.ebs.biocrop.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Admin product payload. Product listings must have an explicit seller owner. */
public class AdminProductWriteRequest extends SellerProductWriteRequest {
    @NotBlank @Size(max = 24) @Pattern(regexp = "^[a-fA-F0-9]{24}$", message = "Seller ID must be a valid MongoDB ObjectId")
    private String sellerId;

    public String getSellerId() { return sellerId; }
    public void setSellerId(String sellerId) { this.sellerId = sellerId; }
}
