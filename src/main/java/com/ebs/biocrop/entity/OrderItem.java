package com.ebs.biocrop.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Field;

/** Immutable-at-purchase product and variant snapshot embedded in an order. */
public class OrderItem {
    @Id
    @Field("_id")
    private String id;
    private String productId;
    private String variantId;
    private String productCode;
    private String sku;
    private String title;
    private String vendor;
    private String image;
    private String variant;
    private Integer quantity;
    private Double listPrice;
    private Double unitPrice;
    private Double lineTotal;

    public OrderItem() { }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }
    public String getVariantId() { return variantId; }
    public void setVariantId(String variantId) { this.variantId = variantId; }
    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getVendor() { return vendor; }
    public void setVendor(String vendor) { this.vendor = vendor; }
    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }
    public String getVariant() { return variant; }
    public void setVariant(String variant) { this.variant = variant; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public Double getListPrice() { return listPrice; }
    public void setListPrice(Double listPrice) { this.listPrice = listPrice; }
    public Double getUnitPrice() { return unitPrice; }
    public void setUnitPrice(Double unitPrice) { this.unitPrice = unitPrice; }
    public Double getLineTotal() { return lineTotal; }
    public void setLineTotal(Double lineTotal) { this.lineTotal = lineTotal; }
}
