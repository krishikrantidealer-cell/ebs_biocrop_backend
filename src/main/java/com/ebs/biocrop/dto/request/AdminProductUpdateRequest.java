package com.ebs.biocrop.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Single unified request payload for admin product updates.
 * Supports full product updates (when all fields are provided) as well as
 * partial updates (when only one or a subset of fields are provided).
 * Unprovided (null) fields remain untouched.
 */
public class AdminProductUpdateRequest {

    @Size(max = 24)
    @Pattern(regexp = "^[a-fA-F0-9]{24}$", message = "Seller ID must be a valid MongoDB ObjectId")
    private String sellerId;

    @JsonIgnore @Size(max = 80)
    private String sku;

    @Size(max = 32)
    private String hsnCode;

    @Size(max = 200)
    private String title;

    @Size(max = 300)
    private String technicalName;

    @Size(max = 100000)
    private String description;

    @Size(max = 24)
    private String categoryId;

    @Size(max = 2000)
    private String refundPolicy;

    @PositiveOrZero
    private Double productWeight;

    @Size(max = 10)
    private String productWeightUnit;

    @Size(max = 50)
    @Valid
    private List<SellerProductWriteRequest.VariantWriteRequest> variants;

    public boolean hasChanges() {
        return (sellerId != null && !sellerId.isBlank())
                || (sku != null && !sku.isBlank())
                || hsnCode != null
                || (title != null && !title.isBlank())
                || technicalName != null
                || description != null
                || (categoryId != null && !categoryId.isBlank())
                || refundPolicy != null
                || productWeight != null
                || productWeightUnit != null
                || (variants != null && !variants.isEmpty());
    }

    public String getSellerId() { return sellerId; }
    public void setSellerId(String sellerId) { this.sellerId = sellerId; }

    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }

    public String getHsnCode() { return hsnCode; }
    public void setHsnCode(String hsnCode) { this.hsnCode = hsnCode; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getTechnicalName() { return technicalName; }
    public void setTechnicalName(String technicalName) { this.technicalName = technicalName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCategoryId() { return categoryId; }
    public void setCategoryId(String categoryId) { this.categoryId = categoryId; }

    public String getRefundPolicy() { return refundPolicy; }
    public void setRefundPolicy(String refundPolicy) { this.refundPolicy = refundPolicy; }

    public Double getProductWeight() { return productWeight; }
    public void setProductWeight(Double productWeight) { this.productWeight = productWeight; }

    public String getProductWeightUnit() { return productWeightUnit; }
    public void setProductWeightUnit(String productWeightUnit) { this.productWeightUnit = productWeightUnit; }

    public List<SellerProductWriteRequest.VariantWriteRequest> getVariants() { return variants; }
    public void setVariants(List<SellerProductWriteRequest.VariantWriteRequest> variants) { this.variants = variants; }
}
