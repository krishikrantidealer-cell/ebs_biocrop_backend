package com.ebs.biocrop.dto.request;

import com.ebs.biocrop.entity.ProductVariant;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import com.ebs.biocrop.entity.enums.PaymentMethod;
import jakarta.validation.constraints.Size;

/** Full replacement payload for one product variant. */
public class AdminProductVariantWriteRequest {
    @Size(max = 160) private String label;
    @NotBlank @Size(max = 20) @com.fasterxml.jackson.annotation.JsonAlias("packSizeUnit") private String unit;
    @NotNull @Positive private Integer packSize;
    @NotNull @Positive private Integer packQuantity;
    @Size(max = 120) private String shippedBy;
    @Size(max = 80) private String shippingThrough;
    @PositiveOrZero private Double swg;
    @PositiveOrZero private Double productWeight;
    @Size(max = 10) private String productWeightUnit;
    @Size(max = 10) @jakarta.validation.Valid private java.util.List<com.ebs.biocrop.entity.ProductDimensions> dimensions;
    @NotNull @PositiveOrZero private Double displayRate;
    @PositiveOrZero private Double printedMrp;
    @PositiveOrZero @DecimalMax("100") private Double commissionPercentage;
    @PositiveOrZero private Double courierCharges;
    @PositiveOrZero @DecimalMax("100") private Double gstPercentage;
    @PositiveOrZero private Double discountOnMrpRs;
    @PositiveOrZero @DecimalMax("100") private Double discountPercentage;
    @NotNull @PositiveOrZero private Integer stock;
    private PaymentMethod paymentMethod = PaymentMethod.PREPAID;
    private Boolean isPrepaidAllowed = true;
    private Boolean isPartialPaymentAllowed = true;
    @DecimalMin("10") @DecimalMax("99") private Double minimumPartialPaymentPercentage = 10.0;
    @PositiveOrZero private Double codCharge = 60.0;
    private Boolean isDefault;
    @JsonIgnore @Size(max = 80) private String sku;

    public ProductVariant toProductVariant() {
        ProductVariant value = new ProductVariant();
        value.setSku(sku);
        value.setLabel(label); value.setUnit(unit); value.setPackSize(packSize);
        value.setPackQuantity(packQuantity);
        value.setShippedBy(shippedBy); value.setShippingThrough(shippingThrough); value.setSwg(swg);
        value.setProductWeight(productWeight); value.setProductWeightUnit(productWeightUnit);
        value.setDimensions(dimensions);
        value.setDisplayRate(displayRate); value.setPrintedMrp(printedMrp);
        value.setCommissionPercentage(commissionPercentage); value.setCourierCharges(courierCharges);
        value.setGstPercentage(gstPercentage); value.setDiscountOnMrpRs(discountOnMrpRs);
        value.setDiscountPercentage(discountPercentage); value.setStock(stock); value.setPaymentMethod(paymentMethod);
        value.setIsPrepaidAllowed(isPrepaidAllowed); value.setIsPartialPaymentAllowed(isPartialPaymentAllowed);
        value.setMinimumPartialPaymentPercentage(minimumPartialPaymentPercentage);
        value.setCodCharge(codCharge);
        if (isDefault != null) value.setIsDefault(isDefault);
        return value;
    }

    public String getLabel() { return label; } public void setLabel(String v) { label=v; }
    public String getUnit() { return unit; } public void setUnit(String v) { unit=v; }
    public Integer getPackSize() { return packSize; } public void setPackSize(Integer v) { packSize=v; }
    public Integer getPackQuantity() { return packQuantity; } public void setPackQuantity(Integer v) { packQuantity=v; }
    public String getShippedBy() { return shippedBy; } public void setShippedBy(String v) { shippedBy=v; }
    public String getShippingThrough() { return shippingThrough; } public void setShippingThrough(String v) { shippingThrough=v; }
    public Double getSwg() { return swg; } public void setSwg(Double v) { swg=v; }
    public Double getProductWeight() { return productWeight; } public void setProductWeight(Double v) { productWeight=v; }
    public String getProductWeightUnit() { return productWeightUnit; } public void setProductWeightUnit(String v) { productWeightUnit=v; }
    public java.util.List<com.ebs.biocrop.entity.ProductDimensions> getDimensions() { return dimensions; } public void setDimensions(java.util.List<com.ebs.biocrop.entity.ProductDimensions> v) { dimensions=v; }
    public Double getDisplayRate() { return displayRate; } public void setDisplayRate(Double v) { displayRate=v; }
    public Double getDiscountOnMrpRs() { return discountOnMrpRs; } public void setDiscountOnMrpRs(Double v) { discountOnMrpRs=v; }
    public Double getDiscountPercentage() { return discountPercentage; } public void setDiscountPercentage(Double v) { discountPercentage=v; }
    public Double getPrintedMrp() { return printedMrp; } public void setPrintedMrp(Double v) { printedMrp=v; }
    public Double getCommissionPercentage() { return commissionPercentage; } public void setCommissionPercentage(Double v) { commissionPercentage=v; }
    public Double getCourierCharges() { return courierCharges; } public void setCourierCharges(Double v) { courierCharges=v; }
    public Double getGstPercentage() { return gstPercentage; } public void setGstPercentage(Double v) { gstPercentage=v; }
    public PaymentMethod getPaymentMethod() { return paymentMethod; } public void setPaymentMethod(PaymentMethod v) { paymentMethod=v; }
    public Boolean getIsPrepaidAllowed() { return isPrepaidAllowed; } public void setIsPrepaidAllowed(Boolean v) { isPrepaidAllowed=v; }
    public Boolean getIsPartialPaymentAllowed() { return isPartialPaymentAllowed; } public void setIsPartialPaymentAllowed(Boolean v) { isPartialPaymentAllowed=v; }
    public Double getMinimumPartialPaymentPercentage() { return minimumPartialPaymentPercentage; } public void setMinimumPartialPaymentPercentage(Double v) { minimumPartialPaymentPercentage=v; }
    public Double getCodCharge() { return codCharge; } public void setCodCharge(Double v) { codCharge=v; }
    public Integer getStock() { return stock; } public void setStock(Integer v) { stock=v; }
    public Boolean getIsDefault() { return isDefault; } public void setIsDefault(Boolean v) { isDefault=v; }
    public String getSku() { return sku; } public void setSku(String v) { sku=v; }
}
