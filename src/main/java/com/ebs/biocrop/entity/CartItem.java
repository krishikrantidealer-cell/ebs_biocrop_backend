package com.ebs.biocrop.entity;

import org.bson.types.ObjectId;
import java.util.UUID;

public class CartItem {

    @org.springframework.data.mongodb.core.mapping.Field("_id")
    private String id;
    private String productId;
    private String variantId;
    private Integer quantity;
    private Double price;

    public CartItem() {
        this.id = new ObjectId().toHexString();
    }

    public CartItem(String productId, String variantId, Integer quantity, Double price) {
        this();
        this.productId = productId;
        this.variantId = variantId;
        this.quantity = quantity;
        this.price = price;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getVariantId() {
        return variantId;
    }

    public void setVariantId(String variantId) {
        this.variantId = variantId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public Double getPrice() {
        return price;
    }

    public void setPrice(Double price) {
        this.price = price;
    }
}
