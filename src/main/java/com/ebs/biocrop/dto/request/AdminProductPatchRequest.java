package com.ebs.biocrop.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/** Nullable fields are left unchanged; provided fields replace their current value. */
public class AdminProductPatchRequest {
    @JsonIgnore @Size(max = 80) private String sku;
    @Size(max = 32) private String hsnCode;
    @Size(max = 200) private String title;
    @Size(max = 120) private String brandName;
    @Size(max = 300) private String technicalName;
    @Size(max = 100000) private String description;
    @Size(max = 24) private String categoryId;
    @Size(max = 2000) private String refundPolicy;
    @PositiveOrZero private Double productWeight;
    @Size(max = 10) private String productWeightUnit;
    @Size(max = 50) @Valid private List<SellerProductWriteRequest.VariantWriteRequest> variants;

    public String getSku() { return sku; } public void setSku(String v) { sku=v; }
    public String getHsnCode() { return hsnCode; } public void setHsnCode(String v) { hsnCode=v; }
    public String getTitle() { return title; } public void setTitle(String v) { title=v; }
    public String getBrandName() { return brandName; } public void setBrandName(String v) { brandName=v; }
    public String getTechnicalName() { return technicalName; } public void setTechnicalName(String v) { technicalName=v; }
    public String getDescription() { return description; } public void setDescription(String v) { description=v; }
    public String getCategoryId() { return categoryId; } public void setCategoryId(String v) { categoryId=v; }
    public String getRefundPolicy() { return refundPolicy; } public void setRefundPolicy(String v) { refundPolicy=v; }
    public Double getProductWeight() { return productWeight; } public void setProductWeight(Double v) { productWeight=v; }
    public String getProductWeightUnit() { return productWeightUnit; } public void setProductWeightUnit(String v) { productWeightUnit=v; }
    public List<SellerProductWriteRequest.VariantWriteRequest> getVariants() { return variants; } public void setVariants(List<SellerProductWriteRequest.VariantWriteRequest> v) { variants=v; }

    public boolean hasChanges() {
        return sku != null || hsnCode != null || title != null || brandName != null || technicalName != null
                || description != null || categoryId != null
                || refundPolicy != null || productWeight != null || productWeightUnit != null
                || (variants != null && !variants.isEmpty());
    }
}
