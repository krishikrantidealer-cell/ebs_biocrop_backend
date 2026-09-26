package com.ebs.biocrop.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

@Document(collection = "wishlists")
public class Wishlist {

    @Id
    private String id;

    @Indexed(name = "uniq_wishlist_user", unique = true)
    private String user;

    private List<String> variantIds = new ArrayList<>();
    private Map<String, String> variantProductIds = new HashMap<>();
    @Field("products")
    private List<String> legacyProductIds = new ArrayList<>();
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Wishlist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUser() { return user; }
    public void setUser(String user) { this.user = user; }

    public List<String> getVariantIds() { return variantIds; }
    public void setVariantIds(List<String> variantIds) {
        this.variantIds = variantIds != null ? variantIds : new ArrayList<>();
    }

    public Map<String, String> getVariantProductIds() { return variantProductIds; }
    public void setVariantProductIds(Map<String, String> variantProductIds) {
        this.variantProductIds = variantProductIds != null ? variantProductIds : new HashMap<>();
    }

    public List<String> getLegacyProductIds() { return legacyProductIds; }
    public void setLegacyProductIds(List<String> legacyProductIds) {
        this.legacyProductIds = legacyProductIds != null ? legacyProductIds : new ArrayList<>();
    }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
