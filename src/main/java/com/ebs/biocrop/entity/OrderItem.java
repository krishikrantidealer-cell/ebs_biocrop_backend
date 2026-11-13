package com.ebs.biocrop.entity;

import org.springframework.data.mongodb.core.mapping.Field;
import java.math.BigDecimal;

/** One independently addressable purchase line and its immutable checkout-time values. */
public class OrderItem {
    @Field(write = Field.Write.ALWAYS) private String itemOrderId;
    @Field(write = Field.Write.ALWAYS) private String sellerFulfillmentId;
    @Field(write = Field.Write.ALWAYS) private String sellerId;
    @Field(write = Field.Write.ALWAYS) private String productId;
    @Field(write = Field.Write.ALWAYS) private String categoryId;
    @Field(write = Field.Write.ALWAYS) private String variantId;
    @Field(write = Field.Write.ALWAYS) private String productCode;
    @Field(write = Field.Write.ALWAYS) private String productSku;
    @Field(write = Field.Write.ALWAYS) private String variantSku;
    @Field(write = Field.Write.ALWAYS) private VariantSnapshot variantSnapshot = new VariantSnapshot();
    @Field(write = Field.Write.ALWAYS) private Order.ProductSnapshot productSnapshot = new Order.ProductSnapshot();
    @Field(write = Field.Write.ALWAYS) private Integer quantity = 0;
    @Field(write = Field.Write.ALWAYS) private BigDecimal listPrice = BigDecimal.ZERO;
    @Field(write = Field.Write.ALWAYS) private BigDecimal discountAmount = BigDecimal.ZERO;
    @Field(write = Field.Write.ALWAYS) private BigDecimal discountPercentage;
    @Field(write = Field.Write.ALWAYS) private BigDecimal unitPrice = BigDecimal.ZERO;
    @Field(write = Field.Write.ALWAYS) private BigDecimal taxAmount;
    @Field(write = Field.Write.ALWAYS) private BigDecimal lineTotal = BigDecimal.ZERO;
    @Field(write = Field.Write.ALWAYS) private BigDecimal advanceAllocatedAtCheckout = BigDecimal.ZERO;
    @Field(write = Field.Write.ALWAYS) private BigDecimal codDueAtCheckout = BigDecimal.ZERO;
    @Field(write = Field.Write.ALWAYS) private String itemStatus = "PENDING_PAYMENT";
    @Field(write = Field.Write.ALWAYS) private Order.ItemCancellation cancellation = new Order.ItemCancellation();

    public String getItemOrderId() { return itemOrderId; }
    public void setItemOrderId(String value) { itemOrderId = value; }
    @org.springframework.data.annotation.Transient @com.fasterxml.jackson.annotation.JsonIgnore public String getId() { return itemOrderId; }
    public void setId(String value) { itemOrderId = value; }
    public String getSellerFulfillmentId() { return sellerFulfillmentId; }
    public void setSellerFulfillmentId(String value) { sellerFulfillmentId = value; }
    public String getSellerId() { return sellerId; }
    public void setSellerId(String value) { sellerId = value; }
    public String getProductId() { return productId; }
    public void setProductId(String value) { productId = value; }
    public String getCategoryId() { return categoryId; }
    public void setCategoryId(String value) { categoryId = value; }
    public String getVariantId() { return variantId; }
    public void setVariantId(String value) { variantId = value; }
    public String getProductCode() { return productCode; }
    public void setProductCode(String value) { productCode = value; }
    public String getProductSku() { return productSku; }
    public void setProductSku(String value) { productSku = value; }
    public String getVariantSku() { return variantSku; }
    public void setVariantSku(String value) { variantSku = value; }
    public VariantSnapshot getVariantSnapshot() { return variantSnapshot; }
    public void setVariantSnapshot(VariantSnapshot value) { variantSnapshot = value == null ? new VariantSnapshot() : value; }
    public Order.ProductSnapshot getProductSnapshot() { return productSnapshot; }
    public void setProductSnapshot(Order.ProductSnapshot value) { productSnapshot = value == null ? new Order.ProductSnapshot() : value; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer value) { quantity = value == null ? 0 : value; }
    public BigDecimal getListPrice() { return listPrice; }
    public void setListPrice(BigDecimal value) { listPrice = zero(value); }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal value) { discountAmount = zero(value); }
    public BigDecimal getDiscountPercentage() { return discountPercentage; }
    public void setDiscountPercentage(BigDecimal value) { discountPercentage = value; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal value) { unitPrice = zero(value); }
    public BigDecimal getTaxAmount() { return taxAmount; }
    public void setTaxAmount(BigDecimal value) { taxAmount = value; }
    public BigDecimal getLineTotal() { return lineTotal; }
    public void setLineTotal(BigDecimal value) { lineTotal = zero(value); }
    public BigDecimal getAdvanceAllocatedAtCheckout() { return advanceAllocatedAtCheckout; }
    public void setAdvanceAllocatedAtCheckout(BigDecimal value) { advanceAllocatedAtCheckout = zero(value); }
    public BigDecimal getCodDueAtCheckout() { return codDueAtCheckout; }
    public void setCodDueAtCheckout(BigDecimal value) { codDueAtCheckout = zero(value); }
    public String getItemStatus() { return itemStatus; }
    public void setItemStatus(String value) { itemStatus = value; }
    public Order.ItemCancellation getCancellation() { return cancellation; }
    public void setCancellation(Order.ItemCancellation value) { cancellation = value == null ? new Order.ItemCancellation() : value; }

    private static BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    /** Variant identity and packaging values copied at checkout so catalog edits cannot rewrite order history. */
    public static class VariantSnapshot {
        @Field(write = Field.Write.ALWAYS) private String label;
        @Field(write = Field.Write.ALWAYS) private String unit;
        @Field(write = Field.Write.ALWAYS) private Integer packSize;
        @Field(write = Field.Write.ALWAYS) private String packSizeUnit;
        @Field(write = Field.Write.ALWAYS) private Integer packQuantity;

        public String getLabel() { return label; }
        public void setLabel(String value) { label = value; }
        public String getUnit() { return unit; }
        public void setUnit(String value) { unit = value; }
        public Integer getPackSize() { return packSize; }
        public void setPackSize(Integer value) { packSize = value; }
        public String getPackSizeUnit() { return packSizeUnit; }
        public void setPackSizeUnit(String value) { packSizeUnit = value; }
        public Integer getPackQuantity() { return packQuantity; }
        public void setPackQuantity(Integer value) { packQuantity = value; }
    }
}
