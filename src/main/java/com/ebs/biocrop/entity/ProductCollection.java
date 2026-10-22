package com.ebs.biocrop.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Merchandising collection definition; product membership remains on Product.assignedCollections. */
@Document(collection = "collections")
public class ProductCollection {
    @Id
    private String id;
    private String name;
    @Indexed(unique = true, name = "uniq_collection_slug")
    private String slug;
    private String description;
    private Boolean isActive = true;
    private Integer priority = 0;
    private List<SubCollection> subCollections = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;
    private String bannerTitle;
    @Version
    @Field("__v")
    private Integer version;

    public ProductCollection() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean active) { isActive = active; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public List<SubCollection> getSubCollections() { return subCollections; }
    public void setSubCollections(List<SubCollection> subCollections) { this.subCollections = subCollections == null ? new ArrayList<>() : subCollections; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getBannerTitle() { return bannerTitle; }
    public void setBannerTitle(String bannerTitle) { this.bannerTitle = bannerTitle; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public static class SubCollection {
        @Field("_id")
        private ObjectId id;
        private String name;
        private String slug;
        private Boolean isActive = true;
        private String image;

        public SubCollection() { }
        public ObjectId getId() { return id; }
        public void setId(ObjectId id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getSlug() { return slug; }
        public void setSlug(String slug) { this.slug = slug; }
        public Boolean getIsActive() { return isActive; }
        public void setIsActive(Boolean active) { isActive = active; }
        public String getImage() { return image; }
        public void setImage(String image) { this.image = image; }
    }
}
