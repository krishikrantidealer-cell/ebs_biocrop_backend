package com.ebs.biocrop.dto.request;

import com.ebs.biocrop.entity.ProductDimensions;
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

    @Size(max = 80)
    private String sku;

    @Size(max = 32)
    private String hsnCode;

    @Size(max = 200)
    private String title;

    @Size(max = 300)
    private String technicalName;

    @Size(max = 10000)
    private String description;

    @Size(max = 20)
    private List<@NotBlank @Size(max = 2048) String> images;

    @Size(max = 10000)
    private String technicalContent;

    @Size(max = 50)
    private List<@Size(max = 300) String> features;

    @Size(max = 50)
    private List<@Size(max = 300) String> benefits;

    @Size(max = 5000)
    private String modeOfAction;

    @Size(max = 50)
    private List<@Size(max = 160) String> suitableCrops;

    @Size(max = 50)
    private List<@Size(max = 160) String> targetPests;

    @Size(max = 50)
    private List<@Size(max = 160) String> targetDiseases;

    @Size(max = 2000)
    private String dosage;

    @Size(max = 2000)
    private String applicationMethod;

    @Size(max = 24)
    private String categoryId;

    @Valid
    private ProductDimensions dimensions;

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
                || images != null
                || technicalContent != null
                || features != null
                || benefits != null
                || modeOfAction != null
                || suitableCrops != null
                || targetPests != null
                || targetDiseases != null
                || dosage != null
                || applicationMethod != null
                || (categoryId != null && !categoryId.isBlank())
                || dimensions != null
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

    public List<String> getImages() { return images; }
    public void setImages(List<String> images) { this.images = images; }

    public String getTechnicalContent() { return technicalContent; }
    public void setTechnicalContent(String technicalContent) { this.technicalContent = technicalContent; }

    public List<String> getFeatures() { return features; }
    public void setFeatures(List<String> features) { this.features = features; }

    public List<String> getBenefits() { return benefits; }
    public void setBenefits(List<String> benefits) { this.benefits = benefits; }

    public String getModeOfAction() { return modeOfAction; }
    public void setModeOfAction(String modeOfAction) { this.modeOfAction = modeOfAction; }

    public List<String> getSuitableCrops() { return suitableCrops; }
    public void setSuitableCrops(List<String> suitableCrops) { this.suitableCrops = suitableCrops; }

    public List<String> getTargetPests() { return targetPests; }
    public void setTargetPests(List<String> targetPests) { this.targetPests = targetPests; }

    public List<String> getTargetDiseases() { return targetDiseases; }
    public void setTargetDiseases(List<String> targetDiseases) { this.targetDiseases = targetDiseases; }

    public String getDosage() { return dosage; }
    public void setDosage(String dosage) { this.dosage = dosage; }

    public String getApplicationMethod() { return applicationMethod; }
    public void setApplicationMethod(String applicationMethod) { this.applicationMethod = applicationMethod; }

    public String getCategoryId() { return categoryId; }
    public void setCategoryId(String categoryId) { this.categoryId = categoryId; }

    public ProductDimensions getDimensions() { return dimensions; }
    public void setDimensions(ProductDimensions dimensions) { this.dimensions = dimensions; }

    public String getRefundPolicy() { return refundPolicy; }
    public void setRefundPolicy(String refundPolicy) { this.refundPolicy = refundPolicy; }

    public Double getProductWeight() { return productWeight; }
    public void setProductWeight(Double productWeight) { this.productWeight = productWeight; }

    public String getProductWeightUnit() { return productWeightUnit; }
    public void setProductWeightUnit(String productWeightUnit) { this.productWeightUnit = productWeightUnit; }

    public List<SellerProductWriteRequest.VariantWriteRequest> getVariants() { return variants; }
    public void setVariants(List<SellerProductWriteRequest.VariantWriteRequest> variants) { this.variants = variants; }
}
