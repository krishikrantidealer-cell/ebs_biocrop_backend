package com.ebs.biocrop.dto.response;

import java.time.LocalDateTime;
import java.util.List;

public class WishlistResponse {

    private int count;
    private List<Item> items = List.of();
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public WishlistResponse() {}

    public WishlistResponse(List<Item> items, LocalDateTime createdAt, LocalDateTime updatedAt) {
        setItems(items);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public int getCount() { return count; }
    public List<Item> getItems() { return items; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setItems(List<Item> items) {
        this.items = items != null ? List.copyOf(items) : List.of();
        this.count = this.items.size();
    }

    public void setCount(int count) { this.count = count; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public static class Item {
        private String productId;
        private String variantId;
        private String productTitle;
        private String brandName;
        private String thumbnail;
        private PublicProductResponse.PublicVariant variant;

        public Item() {}

        public Item(String productId, String variantId, String productTitle,
                    String brandName, String thumbnail, PublicProductResponse.PublicVariant variant) {
            this.productId = productId;
            this.variantId = variantId;
            this.productTitle = productTitle;
            this.brandName = brandName;
            this.thumbnail = thumbnail;
            this.variant = variant;
        }

        public String getProductId() { return productId; }
        public String getVariantId() { return variantId; }
        public String getProductTitle() { return productTitle; }
        public String getBrandName() { return brandName; }
        public String getThumbnail() { return thumbnail; }
        public PublicProductResponse.PublicVariant getVariant() { return variant; }

        public void setProductId(String productId) { this.productId = productId; }
        public void setVariantId(String variantId) { this.variantId = variantId; }
        public void setProductTitle(String productTitle) { this.productTitle = productTitle; }
        public void setBrandName(String brandName) { this.brandName = brandName; }
        public void setThumbnail(String thumbnail) { this.thumbnail = thumbnail; }
        public void setVariant(PublicProductResponse.PublicVariant variant) { this.variant = variant; }
    }
}
