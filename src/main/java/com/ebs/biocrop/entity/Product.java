package com.ebs.biocrop.entity;

import com.ebs.biocrop.entity.enums.PaymentMethod;
import com.ebs.biocrop.entity.enums.ShippingMethod;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import com.fasterxml.jackson.annotation.JsonAlias;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Document(collection = "products")
public class Product {

    @Id
    private String id;

    @JsonAlias("name")
    private String title;
    private String brandName;
    private String technicalName;
    private String thumbnail;
    private String vendor;

    @Indexed
    private String productCode;

    // Categories
    private String categoryId;
    private String subCategoryId;
    private List<String> categoryIds = new ArrayList<>();
    private List<String> subCategoryIds = new ArrayList<>();

    // We keep these legacy fields for mapping Excel safely if needed
    private String company;
    private String category;
    private String subCategory;
    private String keywords;
    private Integer gst;
    private String hsnCode;
    private String availabilityStatus = "In Stock";
    private ShippingMethod shippingThrough;
    private PaymentMethod paymentMethod;
    private String shippedBy;
    private String sourceStatus;
    @JsonAlias("status")
    private String status;
    
    // The nested variants array
    private List<ProductVariant> variants = new ArrayList<>();

    private List<String> images = new ArrayList<>();
    private List<String> mediumImages = new ArrayList<>();
    private List<String> originalImages = new ArrayList<>();
    private Double averageRating = 0.0;
    private Integer numReviews = 0;
    private Integer minPrice;
    private Integer maxPrice;
    private List<String> assignedCollections = new ArrayList<>();
    private Boolean isFeatured = false;
    private String description;
    private List<String> tags = new ArrayList<>();
    private Map<String, Integer> customOrders = new HashMap<>();
    private Integer order = 0;
    private Map<String, String> dosage = new HashMap<>();

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Version
    private Integer version;

    public Product() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getBrandName() { return brandName; }
    public void setBrandName(String brandName) { this.brandName = brandName; }

    public String getTechnicalName() { return technicalName; }
    public void setTechnicalName(String technicalName) { this.technicalName = technicalName; }

    public String getThumbnail() { return thumbnail; }
    public void setThumbnail(String thumbnail) { this.thumbnail = thumbnail; }

    public String getVendor() { return vendor; }
    public void setVendor(String vendor) { this.vendor = vendor; }

    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }

    public String getCategoryId() { return categoryId; }
    public void setCategoryId(String categoryId) { this.categoryId = categoryId; }

    public String getSubCategoryId() { return subCategoryId; }
    public void setSubCategoryId(String subCategoryId) { this.subCategoryId = subCategoryId; }

    public List<String> getCategoryIds() { return categoryIds; }
    public void setCategoryIds(List<String> categoryIds) { this.categoryIds = categoryIds; }

    public List<String> getSubCategoryIds() { return subCategoryIds; }
    public void setSubCategoryIds(List<String> subCategoryIds) { this.subCategoryIds = subCategoryIds; }

    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getSubCategory() { return subCategory; }
    public void setSubCategory(String subCategory) { this.subCategory = subCategory; }

    public String getKeywords() { return keywords; }
    public void setKeywords(String keywords) { this.keywords = keywords; }

    public Integer getGst() { return gst; }
    public void setGst(Integer gst) { this.gst = gst; }

    public String getHsnCode() { return hsnCode; }
    public void setHsnCode(String hsnCode) { this.hsnCode = hsnCode; }

    public String getAvailabilityStatus() { return availabilityStatus; }
    public void setAvailabilityStatus(String availabilityStatus) { this.availabilityStatus = availabilityStatus; }

    public String getSourceStatus() { return sourceStatus; }
    public void setSourceStatus(String sourceStatus) { this.sourceStatus = sourceStatus; }

    public ShippingMethod getShippingThrough() { return shippingThrough; }
    public void setShippingThrough(ShippingMethod shippingThrough) { this.shippingThrough = shippingThrough; }

    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }

    public String getShippedBy() { return shippedBy; }
    public void setShippedBy(String shippedBy) { this.shippedBy = shippedBy; }

    public List<ProductVariant> getVariants() { return variants; }
    public void setVariants(List<ProductVariant> variants) { this.variants = variants; }

    public List<String> getImages() { return images; }
    public void setImages(List<String> images) { this.images = images; }

    public List<String> getMediumImages() { return mediumImages; }
    public void setMediumImages(List<String> mediumImages) { this.mediumImages = mediumImages; }

    public List<String> getOriginalImages() { return originalImages; }
    public void setOriginalImages(List<String> originalImages) { this.originalImages = originalImages; }

    public Double getAverageRating() { return averageRating; }
    public void setAverageRating(Double averageRating) { this.averageRating = averageRating; }

    public Integer getNumReviews() { return numReviews; }
    public void setNumReviews(Integer numReviews) { this.numReviews = numReviews; }

    public Integer getMinPrice() { return minPrice; }
    public void setMinPrice(Integer minPrice) { this.minPrice = minPrice; }

    public Integer getMaxPrice() { return maxPrice; }
    public void setMaxPrice(Integer maxPrice) { this.maxPrice = maxPrice; }

    public List<String> getAssignedCollections() { return assignedCollections; }
    public void setAssignedCollections(List<String> assignedCollections) { this.assignedCollections = assignedCollections; }

    public Boolean getIsFeatured() { return isFeatured; }
    public void setIsFeatured(Boolean isFeatured) { this.isFeatured = isFeatured; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public Map<String, Integer> getCustomOrders() { return customOrders; }
    public void setCustomOrders(Map<String, Integer> customOrders) { this.customOrders = customOrders; }

    public Integer getOrder() { return order; }
    public void setOrder(Integer order) { this.order = order; }

    public Map<String, String> getDosage() { return dosage; }
    public void setDosage(Map<String, String> dosage) { this.dosage = dosage; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
