package com.ebs.biocrop.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Admin product payload. Optional seller owner for direct platform or seller-specific products. */
public class AdminProductWriteRequest extends SellerProductWriteRequest {
    @Size(max = 24)
    @Pattern(regexp = "^$|^[a-fA-F0-9]{24}$", message = "Seller ID must be a valid MongoDB ObjectId")
    private String sellerId;

    @DecimalMin("0.0")
    @DecimalMax("5.0")
    private Double averageRating;

    public String getSellerId() { return sellerId; }
    public void setSellerId(String sellerId) { this.sellerId = sellerId; }
    public Double getAverageRating() { return averageRating; }
    public void setAverageRating(Double averageRating) { this.averageRating = averageRating; }
}
