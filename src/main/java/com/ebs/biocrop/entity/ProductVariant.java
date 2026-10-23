package com.ebs.biocrop.entity;

import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.MongoId;
import org.springframework.data.mongodb.core.mapping.FieldType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** One sellable package option and its price, stock, and package dimensions. */
public class ProductVariant {
    @MongoId(FieldType.STRING)
    private String id;

    private Integer displayOrder;
    private Boolean isDefault;

    // Packaging fields: e.g. 1 litre (250 ml x 4 units).
    @Field(write = Field.Write.ALWAYS)
    private String label;
    /** Product sale/unit label from the catalog schema (for example ml, litre, gm, or kg). */
    @Field(write = Field.Write.ALWAYS)
    private String unit;
    @Field(write = Field.Write.ALWAYS)
    private Integer packSize;
    @Field(write = Field.Write.ALWAYS)
    private String packSizeUnit;
    @Field(write = Field.Write.ALWAYS)
    private Integer packQuantity;
    @Field(write = Field.Write.ALWAYS)
    private String packUnit;
    @Field(write = Field.Write.ALWAYS)
    private String shippedBy;
    /** SWG is stored as provided; interpreted as shipping weight grams, without rate calculation. */
    @Field(write = Field.Write.ALWAYS)
    private Double swg;
    @Field(write = Field.Write.ALWAYS)
    private Long totalBaseQuantity;
    @Field(write = Field.Write.ALWAYS)
    private String totalBaseUnit;
    @Field(write = Field.Write.ALWAYS)
    private ProductDimensions dimensions;

    // Approved schema commercial fields, in currency units and percentage points.
    @Field(write = Field.Write.ALWAYS)
    private Double displayRate;
    @Field(write = Field.Write.ALWAYS)
    private Double printedMrp;
    @Field(write = Field.Write.ALWAYS)
    private Double costPrice;
    @Field(write = Field.Write.ALWAYS)
    private Double discountRs;
    @Field(write = Field.Write.ALWAYS)
    private Double gstPercentage;
    @Field(write = Field.Write.ALWAYS)
    private Double discountPercentage;
    @Field(write = Field.Write.ALWAYS)
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
    public Double getDiscountRs() { return getCalculatedDiscountAmount(); }
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
        // An explicitly supplied amount (including zero) takes precedence over an
        // inferred MRP/display-rate difference. This lets sellers opt out of a
        // discount while retaining the printed MRP for display/compliance.
        if (discountRs != null) {
            return BigDecimal.valueOf(discountRs).max(BigDecimal.ZERO)
                    .setScale(2, RoundingMode.HALF_UP).doubleValue();
        }
        if (discountPercentage != null && printedMrp != null) {
            return BigDecimal.valueOf(printedMrp).multiply(BigDecimal.valueOf(discountPercentage))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP).doubleValue();
        }
        if (printedMrp != null && getEffectivePrice() != null) {
            return BigDecimal.valueOf(printedMrp).subtract(BigDecimal.valueOf(getEffectivePrice())).max(BigDecimal.ZERO)
                    .setScale(2, RoundingMode.HALF_UP).doubleValue();
        }
        if (displayRate != null && discountPercentage != null) {
            return BigDecimal.valueOf(displayRate).multiply(BigDecimal.valueOf(discountPercentage))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP).doubleValue();
        }
        return 0.0;
    }

    public Double getCalculatedDiscountPercent() {
        if (discountPercentage != null) return Math.max(0.0, discountPercentage);
        if (printedMrp == null) {
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
