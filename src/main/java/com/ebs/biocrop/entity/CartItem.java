package com.ebs.biocrop.entity;

import org.bson.types.ObjectId;
import java.util.UUID;

public class CartItem {

    @org.springframework.data.mongodb.core.mapping.Field("_id")
    private String id;
    private String product; // productId
    private String variantId;
    private Integer quantity;
    private Double price;

    public CartItem() {
        this.id = new ObjectId().toHexString();
    }

    public CartItem(String product, String variantId, Integer quantity, Double price) {
        this();
        this.product = product;
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

    public String getProduct() {
        return product;
    }

    public void setProduct(String product) {
        this.product = product;
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
