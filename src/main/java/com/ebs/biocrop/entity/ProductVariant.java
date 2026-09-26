package com.ebs.biocrop.entity;

import com.ebs.biocrop.entity.enums.ProductUnit;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ProductVariant {

    @Id
    @org.springframework.data.mongodb.core.mapping.Field("_id")
    private String id;

    private String variationCode;
    
    // Original properties from Excel mapping
    private ProductUnit unit;
    private Integer unitQty;
    private Double price;
    private Double discountRs;
    private Double salePrice;
    private Double discountPercent;
    private Double courierCharge;
    private Double productWeightGm;
    private Integer stockQty;
    private Integer minOrderQty = 1;
    private Double sellerWillGet;
    private Double length;
    private Double width;
    private Double height;
    private Integer displayOrder;
    private String notes;
    private Boolean isDefault = false;

    // New Fields from JSON requirement (kept empty/default for now)
    private String size; 
    private Integer compareAtPrice;
    private Integer farmerPrice;
    private Integer costPrice;
    private String costRate;
    private Integer packVolume;
    private Integer weight;
    private Map<String, String> rates = new HashMap<>();
    private Map<String, String> computedPrices = new HashMap<>();
    private List<Map<String, Object>> priceTiers = new ArrayList<>();
    private String basePacking;
    private String basePackingUnit;

    public ProductVariant() {
        this.id = new ObjectId().toHexString();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getVariationCode() {
        return variationCode;
    }

    public void setVariationCode(String variationCode) {
        this.variationCode = variationCode;
    }

    public ProductUnit getUnit() {
        return unit;
    }

    public void setUnit(ProductUnit unit) {
        this.unit = unit;
    }

    public Integer getUnitQty() {
        return unitQty;
    }

    public void setUnitQty(Integer unitQty) {
        this.unitQty = unitQty;
    }

    public Double getPrice() {
        return price;
    }

    public void setPrice(Double price) {
        this.price = price;
    }

    public Double getDiscountRs() {
        return discountRs;
    }

    public void setDiscountRs(Double discountRs) {
        this.discountRs = discountRs;
    }

    public Double getSalePrice() {
        return salePrice;
    }

    public void setSalePrice(Double salePrice) {
        this.salePrice = salePrice;
    }

    public Double getDiscountPercent() {
        return discountPercent;
    }

    public void setDiscountPercent(Double discountPercent) {
        this.discountPercent = discountPercent;
    }

    public Double getCourierCharge() {
        return courierCharge;
    }

    public void setCourierCharge(Double courierCharge) {
        this.courierCharge = courierCharge;
    }

    public Double getProductWeightGm() {
        return productWeightGm;
    }

    public void setProductWeightGm(Double productWeightGm) {
        this.productWeightGm = productWeightGm;
    }

    public Integer getStockQty() {
        return stockQty;
    }

    public void setStockQty(Integer stockQty) {
        this.stockQty = stockQty;
    }

    public Integer getMinOrderQty() {
        return minOrderQty;
    }

    public void setMinOrderQty(Integer minOrderQty) {
        this.minOrderQty = minOrderQty;
    }

    public Double getSellerWillGet() {
        return sellerWillGet;
    }

    public void setSellerWillGet(Double sellerWillGet) {
        this.sellerWillGet = sellerWillGet;
    }

    public Double getLength() {
        return length;
    }

    public void setLength(Double length) {
        this.length = length;
    }

    public Double getWidth() {
        return width;
    }

    public void setWidth(Double width) {
        this.width = width;
    }

    public Double getHeight() {
        return height;
    }

    public void setHeight(Double height) {
        this.height = height;
    }

    public Integer getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(Integer displayOrder) {
        this.displayOrder = displayOrder;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Boolean getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(Boolean isDefault) {
        this.isDefault = isDefault;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public Integer getCompareAtPrice() {
        return compareAtPrice;
    }

    public void setCompareAtPrice(Integer compareAtPrice) {
        this.compareAtPrice = compareAtPrice;
    }

    public Integer getFarmerPrice() {
        return farmerPrice;
    }

    public void setFarmerPrice(Integer farmerPrice) {
        this.farmerPrice = farmerPrice;
    }

    public Integer getCostPrice() {
        return costPrice;
    }

    public void setCostPrice(Integer costPrice) {
        this.costPrice = costPrice;
    }

    public String getCostRate() {
        return costRate;
    }

    public void setCostRate(String costRate) {
        this.costRate = costRate;
    }

    public Integer getPackVolume() {
        return packVolume;
    }

    public void setPackVolume(Integer packVolume) {
        this.packVolume = packVolume;
    }

    public Integer getWeight() {
        return weight;
    }

    public void setWeight(Integer weight) {
        this.weight = weight;
    }

    public Map<String, String> getRates() {
        return rates;
    }

    public void setRates(Map<String, String> rates) {
        this.rates = rates;
    }

    public Map<String, String> getComputedPrices() {
        return computedPrices;
    }

    public void setComputedPrices(Map<String, String> computedPrices) {
        this.computedPrices = computedPrices;
    }

    public List<Map<String, Object>> getPriceTiers() {
        return priceTiers;
    }

    public void setPriceTiers(List<Map<String, Object>> priceTiers) {
        this.priceTiers = priceTiers;
    }

    public String getBasePacking() {
        return basePacking;
    }

    public void setBasePacking(String basePacking) {
        this.basePacking = basePacking;
    }

    public String getBasePackingUnit() {
        return basePackingUnit;
    }

    public void setBasePackingUnit(String basePackingUnit) {
        this.basePackingUnit = basePackingUnit;
    }
}
