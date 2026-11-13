package com.ebs.biocrop.entity;

import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.MongoId;
import org.springframework.data.mongodb.core.mapping.FieldType;
import com.ebs.biocrop.entity.enums.PaymentMethod;
import com.ebs.biocrop.entity.enums.ProductUnit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** One sellable package option and its price, stock, and package dimensions. */
public class ProductVariant {
    @MongoId(FieldType.STRING)
    private String id;

    @Field(write = Field.Write.ALWAYS)
    private Integer displayOrder;
    @Field(write = Field.Write.ALWAYS)
    private Boolean isDefault = false;

    @Field(write = Field.Write.ALWAYS)
    private String sku;

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
    private String shippedBy;
    /** SWG is stored as provided; interpreted as shipping weight grams, without rate calculation. */
    @Field(write = Field.Write.ALWAYS)
    private Double swg;
    @Field(write = Field.Write.ALWAYS)
    private java.util.List<ProductDimensions> dimensions = new java.util.ArrayList<>();

    // Approved schema commercial fields, in currency units and percentage points.
    @Field(write = Field.Write.ALWAYS)
    private Double displayRate;
    @Field(write = Field.Write.ALWAYS)
    private Double printedMrp;
    @Field(write = Field.Write.ALWAYS)
    private Double costPrice;
    @Field(write = Field.Write.ALWAYS)
    private Double amountYouWillReceive;
    @Field(write = Field.Write.ALWAYS)
    private Double commissionPercentage;
    @Field(write = Field.Write.ALWAYS)
    private Double courierCharges;
    @Field(value = "discountOnMrpRs", write = Field.Write.ALWAYS)
    private Double discountOnMrpRs;
    @Field(write = Field.Write.ALWAYS)
    private Double gstPercentage;
    @Field(write = Field.Write.ALWAYS)
    private Double discountPercentage;
    @Field(write = Field.Write.ALWAYS)
    private Integer stock;
    @Field(write = Field.Write.ALWAYS)
    private PaymentMethod paymentMethod = PaymentMethod.ONLINE;
    @Field(write = Field.Write.ALWAYS)
    private Boolean isPrepaidAllowed = true;
    @Field(write = Field.Write.ALWAYS)
    private Boolean isPartialPaymentAllowed = true;
    @Field(write = Field.Write.ALWAYS)
    private Double minimumPartialPaymentPercentage = 10.0;
    @Field(write = Field.Write.ALWAYS)
    private Double codCharge = 30.0;

    public ProductVariant() { }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }
    public Boolean getIsDefault() { return Boolean.TRUE.equals(isDefault); }
    public void setIsDefault(Boolean isDefault) { this.isDefault = Boolean.TRUE.equals(isDefault); }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getUnit() { return unit != null ? unit : packSizeUnit; }
    public void setUnit(String unit) { this.unit = unit; }
    public ProductUnit getProductUnit() { return ProductUnit.fromString(getUnit()); }
    public void setProductUnit(ProductUnit productUnit) { this.unit = productUnit != null ? productUnit.getUnitName() : null; }
    public Integer getPackSize() { return packSize; }
    public void setPackSize(Integer packSize) { this.packSize = packSize; }
    public String getPackSizeUnit() { return packSizeUnit; }
    public void setPackSizeUnit(String packSizeUnit) { this.packSizeUnit = packSizeUnit; }
    public Integer getPackQuantity() { return packQuantity; }
    public void setPackQuantity(Integer packQuantity) { this.packQuantity = packQuantity; }
    public String getShippedBy() { return shippedBy; }
    public void setShippedBy(String shippedBy) { this.shippedBy = shippedBy; }
    public Double getSwg() { return swg; }
    public void setSwg(Double swg) { this.swg = swg; }
    public java.util.List<ProductDimensions> getDimensions() { return dimensions; }
    public void setDimensions(java.util.List<ProductDimensions> dimensions) {
        this.dimensions = dimensions != null ? dimensions : new java.util.ArrayList<>();
    }
    public Double getDisplayRate() { return displayRate; }
    public void setDisplayRate(Double displayRate) { this.displayRate = displayRate; }
    public Double getPrintedMrp() { return printedMrp; }
    public void setPrintedMrp(Double printedMrp) { this.printedMrp = printedMrp; }
    public Double getCostPrice() { return costPrice; }
    public void setCostPrice(Double costPrice) { this.costPrice = costPrice; }
    public Double getAmountYouWillReceive() { return amountYouWillReceive; }
    public void setAmountYouWillReceive(Double amountYouWillReceive) { this.amountYouWillReceive = amountYouWillReceive; }
    public Double getCommissionPercentage() { return commissionPercentage; }
    public void setCommissionPercentage(Double commissionPercentage) { this.commissionPercentage = commissionPercentage; }
    public Double getCourierCharges() { return courierCharges; }
    public void setCourierCharges(Double courierCharges) { this.courierCharges = courierCharges; }
    public Double getDiscountOnMrpRs() { return discountOnMrpRs; }
    public void setDiscountOnMrpRs(Double discountOnMrpRs) { this.discountOnMrpRs = discountOnMrpRs; }
    public Double getGstPercentage() { return gstPercentage; }
    public void setGstPercentage(Double gstPercentage) { this.gstPercentage = gstPercentage; }
    public Double getDiscountPercentage() { return discountPercentage; }
    public void setDiscountPercentage(Double discountPercentage) { this.discountPercentage = discountPercentage; }
    public Integer getStock() { return stock; }
    public void setStock(Integer stock) { this.stock = stock; }
    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }
    public Boolean getIsPrepaidAllowed() { return isPrepaidAllowed; }
    public void setIsPrepaidAllowed(Boolean prepaidAllowed) { isPrepaidAllowed = prepaidAllowed; }
    public Boolean getIsPartialPaymentAllowed() { return isPartialPaymentAllowed; }
    public void setIsPartialPaymentAllowed(Boolean partialPaymentAllowed) { isPartialPaymentAllowed = partialPaymentAllowed; }
    public Double getMinimumPartialPaymentPercentage() { return minimumPartialPaymentPercentage; }
    public void setMinimumPartialPaymentPercentage(Double value) { minimumPartialPaymentPercentage = value; }
    public Double getCodCharge() { return codCharge; }
    public void setCodCharge(Double codCharge) { this.codCharge = codCharge; }

    /** Display price supplied by the authorized product writer. */
    public Double getEffectivePrice() {
        return displayRate;
    }

    /** Selling price divided by normalized total ml or g. */
    public Double getPricePerBaseUnit() {
        Long quantity = null;
        String unitName = packSizeUnit;
        if (packSize != null && packSize > 0) {
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
