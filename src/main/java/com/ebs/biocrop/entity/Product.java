package com.ebs.biocrop.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Product content schema based on the approved product fields. Seller offers are variants. */
@Document(collection = "products")
@CompoundIndexes({
        @CompoundIndex(name = "product_category_status_idx", def = "{ 'categoryId': 1, 'status': 1 }"),
        @CompoundIndex(name = "product_seller_updated_idx", def = "{ 'sellerId': 1, 'updatedAt': -1 }")
})
public class Product {

    @Id
    private String id;

    @Indexed(unique = true, name = "uniq_product_sku")
    private String sku;
    private String productCode;
    private String hsnCode;
    @Indexed
    private String sellerId;
    private String title;
    private String technicalName;
    private String vendor;
    private String description;
    private List<String> images = new ArrayList<>();
    private List<ProductImage> productImages = new ArrayList<>();
    private String technicalContent;
    private List<String> features = new ArrayList<>();
    private List<String> benefits = new ArrayList<>();
    private String modeOfAction;
    private List<String> suitableCrops = new ArrayList<>();
    private List<String> targetPests = new ArrayList<>();
    private List<String> targetDiseases = new ArrayList<>();
    private String dosage;
    private String applicationMethod;

    /** Leaf category document selected for this product/listing. */
    private String categoryId;
    private List<String> collectionIds = new ArrayList<>();
    private List<String> subCollectionIds = new ArrayList<>();
    /** Product-level package dimensions from the approved schema; a variant may override these. */
    private ProductDimensions dimensions;

    private Boolean isAvailable;
    private Boolean isFeatured;
    private List<ProductVariant> variants = new ArrayList<>();
    private Double ratings;
    private String refundPolicy;
    private Double productWeight;
    private String productWeightUnit;

    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Version
    private Integer version;

    public Product() { }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }
    public String getHsnCode() { return hsnCode; }
    public void setHsnCode(String hsnCode) { this.hsnCode = hsnCode; }
    public String getSellerId() { return sellerId; }
    public void setSellerId(String sellerId) { this.sellerId = sellerId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getTechnicalName() { return technicalName; }
    public void setTechnicalName(String technicalName) { this.technicalName = technicalName; }
    public String getVendor() { return vendor; }
    public void setVendor(String vendor) { this.vendor = vendor; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<String> getImages() { return images; }
    public void setImages(List<String> images) { this.images = images != null ? images : new ArrayList<>(); }
    public List<ProductImage> getProductImages() {
        return productImages;
    }
    public void setProductImages(List<ProductImage> productImages) {
        this.productImages = productImages != null ? productImages : new ArrayList<>();
    }
    public String getTechnicalContent() { return technicalContent; }
    public void setTechnicalContent(String technicalContent) { this.technicalContent = technicalContent; }
    public List<String> getFeatures() { return features; }
    public void setFeatures(List<String> features) { this.features = features != null ? features : new ArrayList<>(); }
    public List<String> getBenefits() { return benefits; }
    public void setBenefits(List<String> benefits) { this.benefits = benefits != null ? benefits : new ArrayList<>(); }
    public String getModeOfAction() { return modeOfAction; }
    public void setModeOfAction(String modeOfAction) { this.modeOfAction = modeOfAction; }
    public List<String> getSuitableCrops() { return suitableCrops; }
    public void setSuitableCrops(List<String> suitableCrops) { this.suitableCrops = suitableCrops != null ? suitableCrops : new ArrayList<>(); }
    public List<String> getTargetPests() { return targetPests; }
    public void setTargetPests(List<String> targetPests) { this.targetPests = targetPests != null ? targetPests : new ArrayList<>(); }
    public List<String> getTargetDiseases() { return targetDiseases; }
    public void setTargetDiseases(List<String> targetDiseases) { this.targetDiseases = targetDiseases != null ? targetDiseases : new ArrayList<>(); }
    public String getDosage() { return dosage; }
    public void setDosage(String dosage) { this.dosage = dosage; }
    public String getApplicationMethod() { return applicationMethod; }
    public void setApplicationMethod(String applicationMethod) { this.applicationMethod = applicationMethod; }
    public String getCategoryId() { return categoryId; }
    public void setCategoryId(String categoryId) { this.categoryId = categoryId; }
    public List<String> getCollectionIds() { return collectionIds; }
    public void setCollectionIds(List<String> collectionIds) { this.collectionIds = collectionIds != null ? collectionIds : new ArrayList<>(); }
    public List<String> getSubCollectionIds() { return subCollectionIds; }
    public void setSubCollectionIds(List<String> subCollectionIds) { this.subCollectionIds = subCollectionIds != null ? subCollectionIds : new ArrayList<>(); }
    public ProductDimensions getDimensions() { return dimensions; }
    public void setDimensions(ProductDimensions dimensions) { this.dimensions = dimensions; }
    public Boolean getIsAvailable() { return isAvailable; }
    public void setIsAvailable(Boolean available) { isAvailable = available; }
    public Boolean getIsFeatured() { return isFeatured; }
    public void setIsFeatured(Boolean featured) { isFeatured = featured; }
    public List<ProductVariant> getVariants() { return variants; }
    public void setVariants(List<ProductVariant> variants) { this.variants = variants != null ? variants : new ArrayList<>(); }
    public Double getRatings() { return ratings; }
    public void setRatings(Double ratings) { this.ratings = ratings; }
    public String getRefundPolicy() { return refundPolicy; }
    public void setRefundPolicy(String refundPolicy) { this.refundPolicy = refundPolicy; }
    public Double getProductWeight() { return productWeight; }
    public void setProductWeight(Double productWeight) { this.productWeight = productWeight; }
    public String getProductWeightUnit() { return productWeightUnit; }
    public void setProductWeightUnit(String productWeightUnit) { this.productWeightUnit = productWeightUnit; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
