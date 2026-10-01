package com.ebs.biocrop.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Field;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** One sellable package option and its price, stock, and package dimensions. */
public class ProductVariant {
    @Id
    @Field("_id")
    private String id;

    private Integer displayOrder;
    private Boolean isDefault;

    // Packaging fields: e.g. 1 litre (250 ml x 4 units).
    private String label;
    /** Product sale/unit label from the catalog schema (for example ml, litre, gm, or kg). */
    private String unit;
    private Integer packSize;
    private String packSizeUnit;
    private Integer packQuantity;
    private String packUnit;
    private String shippedBy;
    /** SWG is stored as provided; interpreted as shipping weight grams, without rate calculation. */
    private Double swg;
    private Long totalBaseQuantity;
    private String totalBaseUnit;
    private ProductDimensions dimensions;

    // Approved schema commercial fields, in currency units and percentage points.
    private Double displayRate;
    private Double printedMrp;
    private Double costPrice;
    private Double discountRs;
    private Double gstPercentage;
    private Double discountPercentage;
    private Integer stock;

    public ProductVariant() { }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }
    public Boolean getIsDefault() { return isDefault; }
    public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getUnit() { return unit != null ? unit : packSizeUnit; }
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
    public Long getTotalBaseQuantity() { return totalBaseQuantity; }
    public void setTotalBaseQuantity(Long totalBaseQuantity) { this.totalBaseQuantity = totalBaseQuantity; }
    public String getTotalBaseUnit() { return totalBaseUnit; }
    public void setTotalBaseUnit(String totalBaseUnit) { this.totalBaseUnit = totalBaseUnit; }
    public ProductDimensions getDimensions() { return dimensions; }
    public void setDimensions(ProductDimensions dimensions) { this.dimensions = dimensions; }
    public Double getDisplayRate() { return displayRate; }
    public void setDisplayRate(Double displayRate) { this.displayRate = displayRate; }
    public Double getPrintedMrp() { return printedMrp; }
    public void setPrintedMrp(Double printedMrp) { this.printedMrp = printedMrp; }
    public Double getCostPrice() { return costPrice; }
    public void setCostPrice(Double costPrice) { this.costPrice = costPrice; }
    public Double getDiscountRs() { return printedMrp != null ? getCalculatedDiscountAmount() : discountRs; }
    public void setDiscountRs(Double discountRs) { this.discountRs = discountRs; }
    public Double getGstPercentage() { return gstPercentage; }
    public void setGstPercentage(Double gstPercentage) { this.gstPercentage = gstPercentage; }
    public Double getDiscountPercentage() { return getCalculatedDiscountPercent(); }
    public void setDiscountPercentage(Double discountPercentage) { this.discountPercentage = discountPercentage; }
    public Integer getStock() { return stock; }
    public void setStock(Integer stock) { this.stock = stock; }

    /** Selling price; tax inclusion/exclusion is a platform pricing policy still to be set. */
    public Double getEffectivePrice() {
        return displayRate;
    }

    public Double getCalculatedDiscountAmount() {
        if (printedMrp != null && getEffectivePrice() != null) {
            return BigDecimal.valueOf(printedMrp).subtract(BigDecimal.valueOf(getEffectivePrice())).max(BigDecimal.ZERO)
                    .setScale(2, RoundingMode.HALF_UP).doubleValue();
        }
        if (discountRs != null) return Math.max(0.0, discountRs);
        if (displayRate != null && discountPercentage != null) {
            return BigDecimal.valueOf(displayRate).multiply(BigDecimal.valueOf(discountPercentage))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP).doubleValue();
        }
        return 0.0;
    }

    public Double getCalculatedDiscountPercent() {
        if (printedMrp == null) {
            if (discountPercentage != null) return discountPercentage;
            if (displayRate == null || displayRate <= 0 || discountRs == null) return null;
            return BigDecimal.valueOf(discountRs).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(displayRate), 2, RoundingMode.HALF_UP).doubleValue();
        }
        if (printedMrp <= 0) return 0.0;
        return BigDecimal.valueOf(getCalculatedDiscountAmount()).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(printedMrp), 2, RoundingMode.HALF_UP).doubleValue();
    }

    /** Selling price divided by normalized total ml or g. */
    public Double getPricePerBaseUnit() {
        Long quantity = totalBaseQuantity == null ? null : totalBaseQuantity.longValue();
        String unitName = totalBaseUnit;
        if ((quantity == null || quantity <= 0) && packSize != null && packSize > 0) {
            try {
                quantity = Math.multiplyExact(packSize.longValue(),
                        (long) (packQuantity == null || packQuantity <= 0 ? 1 : packQuantity));
            } catch (ArithmeticException ex) {
                return null;
            }
            unitName = packSizeUnit;
        }
        BigDecimal baseUnits = toBaseUnits(quantity, unitName);
        Double sale = getEffectivePrice();
        if (baseUnits == null || baseUnits.signum() <= 0 || sale == null) return null;
        return BigDecimal.valueOf(sale).divide(baseUnits, 4, RoundingMode.HALF_UP).doubleValue();
    }

    private BigDecimal toBaseUnits(Long quantity, String unitName) {
        if (quantity == null || quantity <= 0 || unitName == null) return null;
        return switch (unitName.trim().toLowerCase(Locale.ROOT)) {
            case "ml", "millilitre", "millilitres", "milliliter", "milliliters",
                    "g", "gm", "gms", "gram", "grams" -> BigDecimal.valueOf(quantity);
            case "mg", "milligram", "milligrams" -> BigDecimal.valueOf(quantity).movePointLeft(3);
            case "l", "lt", "ltr", "litre", "liter", "litres", "liters",
                    "kg", "kilogram", "kilograms" -> BigDecimal.valueOf(quantity).multiply(BigDecimal.valueOf(1000));
            default -> null;
        };
    }
}
