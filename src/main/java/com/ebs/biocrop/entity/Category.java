package com.ebs.biocrop.entity;

import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "categories")
public class Category {

    @Id
    private String id;

    private String name;
    private List<SubCategory> subCategories = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;
    private String bannerImage;
    private String cataloguePdf;
    private String iconImage;
    private String bannerTitle;

    public Category() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<SubCategory> getSubCategories() { return subCategories; }
    public void setSubCategories(List<SubCategory> subCategories) {
        this.subCategories = subCategories != null ? subCategories : new ArrayList<>();
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getBannerImage() { return bannerImage; }
    public void setBannerImage(String bannerImage) { this.bannerImage = bannerImage; }

    public String getCataloguePdf() { return cataloguePdf; }
    public void setCataloguePdf(String cataloguePdf) { this.cataloguePdf = cataloguePdf; }

    public String getIconImage() { return iconImage; }
    public void setIconImage(String iconImage) { this.iconImage = iconImage; }

    public String getBannerTitle() { return bannerTitle; }
    public void setBannerTitle(String bannerTitle) { this.bannerTitle = bannerTitle; }

    public static class SubCategory {

        @Id
        private String id;
        private String name;

        public SubCategory() { this.id = new ObjectId().toHexString(); }

        public SubCategory(String name) {
            this();
            this.name = name;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }
}