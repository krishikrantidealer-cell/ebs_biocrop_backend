package com.ebs.biocrop.dto.request;

import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductDimensions;
import com.ebs.biocrop.entity.ProductVariant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;

/** Seller-controlled product content; server-owned identity, moderation, and metrics are excluded. */
public class SellerProductWriteRequest {
    @NotBlank @Size(max = 80) private String sku;
    @Size(max = 32) private String hsnCode;
    @NotBlank @Size(max = 200) private String title;
    @Size(max = 300) private String technicalName;
    @Size(max = 160) private String vendor;
    @Size(max = 10000) private String description;
    @Size(max = 20) private List<@NotBlank @Size(max = 2048) String> images = new ArrayList<>();
    @Size(max = 10000) private String technicalContent;
    @Size(max = 50) private List<@NotBlank @Size(max = 300) String> features = new ArrayList<>();
    @Size(max = 50) private List<@NotBlank @Size(max = 300) String> benefits = new ArrayList<>();
    @Size(max = 5000) private String modeOfAction;
    @Size(max = 50) private List<@NotBlank @Size(max = 160) String> suitableCrops = new ArrayList<>();
    @Size(max = 50) private List<@NotBlank @Size(max = 160) String> targetPests = new ArrayList<>();
    @Size(max = 50) private List<@NotBlank @Size(max = 160) String> targetDiseases = new ArrayList<>();
    @Size(max = 2000) private String dosage;
    @Size(max = 2000) private String applicationMethod;
    @NotBlank private String categoryId;
    @Size(max = 50) private List<@NotBlank String> collectionIds = new ArrayList<>();
    @Size(max = 50) private List<@NotBlank String> subCollectionIds = new ArrayList<>();
    @Valid private ProductDimensions dimensions;
    @Size(max = 2000) private String refundPolicy;
    @PositiveOrZero private Double productWeight;
    @Size(max = 10) private String productWeightUnit;
    @NotEmpty @Size(max = 50) @Valid private List<VariantWriteRequest> variants;

    public Product toProduct() {
        Product product = new Product();
        product.setSku(sku); product.setHsnCode(hsnCode);
        product.setTitle(title); product.setTechnicalName(technicalName); product.setVendor(vendor);
        product.setDescription(description); product.setImages(images); product.setTechnicalContent(technicalContent);
        product.setFeatures(features); product.setBenefits(benefits); product.setModeOfAction(modeOfAction);
        product.setSuitableCrops(suitableCrops); product.setTargetPests(targetPests); product.setTargetDiseases(targetDiseases);
        product.setDosage(dosage); product.setApplicationMethod(applicationMethod); product.setCategoryId(categoryId);
        product.setCollectionIds(collectionIds); product.setSubCollectionIds(subCollectionIds); product.setDimensions(dimensions);
        product.setRefundPolicy(refundPolicy); product.setProductWeight(productWeight); product.setProductWeightUnit(productWeightUnit);
        product.setVariants(variants == null ? List.of() : variants.stream().map(VariantWriteRequest::toVariant).toList());
        return product;
    }

    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getHsnCode() { return hsnCode; }
    public void setHsnCode(String hsnCode) { this.hsnCode = hsnCode; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getTechnicalName() { return technicalName; }
    public void setTechnicalName(String technicalName) { this.technicalName = technicalName; }
    public String getVendor() { return vendor; }
    public void setVendor(String vendor) { this.vendor = vendor; }
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
    public List<String> getCollectionIds() { return collectionIds; }
    public void setCollectionIds(List<String> collectionIds) { this.collectionIds = collectionIds; }
    public List<String> getSubCollectionIds() { return subCollectionIds; }
    public void setSubCollectionIds(List<String> subCollectionIds) { this.subCollectionIds = subCollectionIds; }
    public ProductDimensions getDimensions() { return dimensions; }
    public void setDimensions(ProductDimensions dimensions) { this.dimensions = dimensions; }
    public String getRefundPolicy() { return refundPolicy; }
    public void setRefundPolicy(String refundPolicy) { this.refundPolicy = refundPolicy; }
    public Double getProductWeight() { return productWeight; }
    public void setProductWeight(Double productWeight) { this.productWeight = productWeight; }
    public String getProductWeightUnit() { return productWeightUnit; }
    public void setProductWeightUnit(String productWeightUnit) { this.productWeightUnit = productWeightUnit; }
    public List<VariantWriteRequest> getVariants() { return variants; }
    public void setVariants(List<VariantWriteRequest> variants) { this.variants = variants; }

    public static class VariantWriteRequest {
        @Size(max = 160) private String label;
        @NotBlank @Size(max = 20) private String unit;
        @NotNull @Positive private Integer packSize;
        @NotBlank @Size(max = 20) private String packSizeUnit;
        @NotNull @Positive private Integer packQuantity;
        @Size(max = 20) private String packUnit;
        @Size(max = 120) private String shippedBy;
        @PositiveOrZero private Double swg;
        @Valid private ProductDimensions dimensions;
        @NotNull @PositiveOrZero private Double displayRate;
        @PositiveOrZero private Double printedMrp;
        @PositiveOrZero private Double costPrice;
        @PositiveOrZero private Double discountRs;
        @PositiveOrZero private Double gstPercentage;
        @PositiveOrZero private Double discountPercentage;
        @NotNull @PositiveOrZero private Integer stock;

        ProductVariant toVariant() {
            ProductVariant variant = new ProductVariant();
            variant.setLabel(label); variant.setUnit(unit); variant.setPackSize(packSize);
            variant.setPackSizeUnit(packSizeUnit); variant.setPackQuantity(packQuantity); variant.setPackUnit(packUnit);
            variant.setShippedBy(shippedBy); variant.setSwg(swg); variant.setDimensions(dimensions);
            variant.setDisplayRate(displayRate); variant.setPrintedMrp(printedMrp); variant.setCostPrice(costPrice);
            variant.setDiscountRs(discountRs); variant.setGstPercentage(gstPercentage);
            variant.setDiscountPercentage(discountPercentage); variant.setStock(stock);
            return variant;
        }

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }
        public Integer getPackSize() { return packSize; }
        public void setPackSize(Integer packSize) { this.packSize = packSize; }
        public String getPackSizeUnit() { return packSizeUnit; }
        public void setPackSizeUnit(String packSizeUnit) { this.packSizeUnit = packSizeUnit; }
        public Integer getPackQuantity() { return packQuantity; }
        public void setPackQuantity(Integer packQuantity) { this.packQuantity = packQuantity; }
        public String getPackUnit() { return packUnit; }
        public void setPackUnit(String packUnit) { this.packUnit = packUnit; }
        public String getShippedBy() { return shippedBy; }
        public void setShippedBy(String shippedBy) { this.shippedBy = shippedBy; }
        public Double getSwg() { return swg; }
        public void setSwg(Double swg) { this.swg = swg; }
        public ProductDimensions getDimensions() { return dimensions; }
        public void setDimensions(ProductDimensions dimensions) { this.dimensions = dimensions; }
        public Double getDisplayRate() { return displayRate; }
        public void setDisplayRate(Double displayRate) { this.displayRate = displayRate; }
        public Double getPrintedMrp() { return printedMrp; }
        public void setPrintedMrp(Double printedMrp) { this.printedMrp = printedMrp; }
        public Double getCostPrice() { return costPrice; }
        public void setCostPrice(Double costPrice) { this.costPrice = costPrice; }
        public Double getDiscountRs() { return discountRs; }
        public void setDiscountRs(Double discountRs) { this.discountRs = discountRs; }
        public Double getGstPercentage() { return gstPercentage; }
        public void setGstPercentage(Double gstPercentage) { this.gstPercentage = gstPercentage; }
        public Double getDiscountPercentage() { return discountPercentage; }
        public void setDiscountPercentage(Double discountPercentage) { this.discountPercentage = discountPercentage; }
        public Integer getStock() { return stock; }
        public void setStock(Integer stock) { this.stock = stock; }
    }
}
