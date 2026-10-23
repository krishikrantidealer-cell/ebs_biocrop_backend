package com.ebs.biocrop.dto.request;

import com.ebs.biocrop.entity.ProductDimensions;
import com.ebs.biocrop.entity.ProductVariant;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Full replacement payload for one product variant. */
public class AdminProductVariantWriteRequest {
    @Size(max = 160) private String label;
    @NotBlank @Size(max = 20) private String unit;
    @NotNull @Positive private Integer packSize;
    @NotBlank @Size(max = 20) private String packSizeUnit;
    @NotNull @Positive private Integer packQuantity;
    @Size(max = 20) private String packUnit;
    @Size(max = 120) private String shippedBy;
    @PositiveOrZero private Double swg;
    private ProductDimensions dimensions;
    @NotNull @PositiveOrZero private Double displayRate;
    @PositiveOrZero private Double printedMrp;
    @PositiveOrZero private Double costPrice;
    @PositiveOrZero private Double discountRs;
    @PositiveOrZero private Double gstPercentage;
    @PositiveOrZero private Double discountPercentage;
    @NotNull @PositiveOrZero private Integer stock;

    public ProductVariant toProductVariant() {
        ProductVariant value = new ProductVariant();
        value.setLabel(label); value.setUnit(unit); value.setPackSize(packSize);
        value.setPackSizeUnit(packSizeUnit); value.setPackQuantity(packQuantity); value.setPackUnit(packUnit);
        value.setShippedBy(shippedBy); value.setSwg(swg); value.setDimensions(dimensions);
        value.setDisplayRate(displayRate); value.setPrintedMrp(printedMrp); value.setCostPrice(costPrice);
        value.setDiscountRs(discountRs); value.setGstPercentage(gstPercentage);
        value.setDiscountPercentage(discountPercentage); value.setStock(stock);
        return value;
    }

    public String getLabel() { return label; } public void setLabel(String v) { label=v; }
    public String getUnit() { return unit; } public void setUnit(String v) { unit=v; }
    public Integer getPackSize() { return packSize; } public void setPackSize(Integer v) { packSize=v; }
    public String getPackSizeUnit() { return packSizeUnit; } public void setPackSizeUnit(String v) { packSizeUnit=v; }
    public Integer getPackQuantity() { return packQuantity; } public void setPackQuantity(Integer v) { packQuantity=v; }
    public String getPackUnit() { return packUnit; } public void setPackUnit(String v) { packUnit=v; }
    public String getShippedBy() { return shippedBy; } public void setShippedBy(String v) { shippedBy=v; }
    public Double getSwg() { return swg; } public void setSwg(Double v) { swg=v; }
    public ProductDimensions getDimensions() { return dimensions; } public void setDimensions(ProductDimensions v) { dimensions=v; }
    public Double getDisplayRate() { return displayRate; } public void setDisplayRate(Double v) { displayRate=v; }
    public Double getPrintedMrp() { return printedMrp; } public void setPrintedMrp(Double v) { printedMrp=v; }
    public Double getCostPrice() { return costPrice; } public void setCostPrice(Double v) { costPrice=v; }
    public Double getDiscountRs() { return discountRs; } public void setDiscountRs(Double v) { discountRs=v; }
    public Double getGstPercentage() { return gstPercentage; } public void setGstPercentage(Double v) { gstPercentage=v; }
    public Double getDiscountPercentage() { return discountPercentage; } public void setDiscountPercentage(Double v) { discountPercentage=v; }
    public Integer getStock() { return stock; } public void setStock(Integer v) { stock=v; }
}
