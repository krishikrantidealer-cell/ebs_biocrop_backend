package com.ebs.biocrop.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Nullable fields are left unchanged; provided fields replace their current value. */
public class AdminProductPatchRequest {
    @Size(max = 80) private String sku;
    @Size(max = 32) private String hsnCode;
    @Size(max = 200) private String title;
    @Size(max = 300) private String technicalName;
    @Size(max = 10000) private String description;
    @Size(max = 20) private List<@NotBlank @Size(max = 2048) String> images;
    @Size(max = 10000) private String technicalContent;
    @Size(max = 50) private List<@NotBlank @Size(max = 300) String> features;
    @Size(max = 50) private List<@NotBlank @Size(max = 300) String> benefits;
    @Size(max = 5000) private String modeOfAction;
    @Size(max = 50) private List<@NotBlank @Size(max = 160) String> suitableCrops;
    @Size(max = 50) private List<@NotBlank @Size(max = 160) String> targetPests;
    @Size(max = 50) private List<@NotBlank @Size(max = 160) String> targetDiseases;
    @Size(max = 2000) private String dosage;
    @Size(max = 2000) private String applicationMethod;
    @Size(max = 24) private String categoryId;
    @Valid private com.ebs.biocrop.entity.ProductDimensions dimensions;
    @Size(max = 2000) private String refundPolicy;
    @PositiveOrZero private Double productWeight;
    @Size(max = 10) private String productWeightUnit;

    public String getSku() { return sku; } public void setSku(String v) { sku=v; }
    public String getHsnCode() { return hsnCode; } public void setHsnCode(String v) { hsnCode=v; }
    public String getTitle() { return title; } public void setTitle(String v) { title=v; }
    public String getTechnicalName() { return technicalName; } public void setTechnicalName(String v) { technicalName=v; }
    public String getDescription() { return description; } public void setDescription(String v) { description=v; }
    public List<String> getImages() { return images; } public void setImages(List<String> v) { images=v; }
    public String getTechnicalContent() { return technicalContent; } public void setTechnicalContent(String v) { technicalContent=v; }
    public List<String> getFeatures() { return features; } public void setFeatures(List<String> v) { features=v; }
    public List<String> getBenefits() { return benefits; } public void setBenefits(List<String> v) { benefits=v; }
    public String getModeOfAction() { return modeOfAction; } public void setModeOfAction(String v) { modeOfAction=v; }
    public List<String> getSuitableCrops() { return suitableCrops; } public void setSuitableCrops(List<String> v) { suitableCrops=v; }
    public List<String> getTargetPests() { return targetPests; } public void setTargetPests(List<String> v) { targetPests=v; }
    public List<String> getTargetDiseases() { return targetDiseases; } public void setTargetDiseases(List<String> v) { targetDiseases=v; }
    public String getDosage() { return dosage; } public void setDosage(String v) { dosage=v; }
    public String getApplicationMethod() { return applicationMethod; } public void setApplicationMethod(String v) { applicationMethod=v; }
    public String getCategoryId() { return categoryId; } public void setCategoryId(String v) { categoryId=v; }
    public com.ebs.biocrop.entity.ProductDimensions getDimensions() { return dimensions; } public void setDimensions(com.ebs.biocrop.entity.ProductDimensions v) { dimensions=v; }
    public String getRefundPolicy() { return refundPolicy; } public void setRefundPolicy(String v) { refundPolicy=v; }
    public Double getProductWeight() { return productWeight; } public void setProductWeight(Double v) { productWeight=v; }
    public String getProductWeightUnit() { return productWeightUnit; } public void setProductWeightUnit(String v) { productWeightUnit=v; }

    public boolean hasChanges() {
        return sku != null || hsnCode != null || title != null || technicalName != null
                || description != null || images != null || technicalContent != null || features != null
                || benefits != null || modeOfAction != null || suitableCrops != null || targetPests != null
                || targetDiseases != null || dosage != null || applicationMethod != null || categoryId != null
                || dimensions != null || refundPolicy != null || productWeight != null || productWeightUnit != null;
    }
}
